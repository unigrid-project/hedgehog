/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.s3.index;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/* A run page, sealed with a CRC32C: the entry count, then per entry how many leading bytes its key shares with the
   key before it, the rest of the key and the value, which a tombstone lacks. Sharing restarts with every page so
   each decodes on its own. Decoded, it holds its keys in order with their values, a null value for a tombstone. */
record Page(byte[][] keys, byte[][] values, long weight) implements PageCache.Weighed {
	private static final int SMALLEST_ENTRY = 3;
	private static final int ENTRY_OVERHEAD = 48;

	static byte[] encode(final List<Slot> slots) {
		return Codec.seal(Codec.encode(output -> {
			byte[] previous = new byte[0];

			Codec.writeNumber(output, slots.size());

			for (Slot slot : slots) {
				final int shared = Math.max(0, Arrays.mismatch(previous, slot.key()));

				Codec.writeNumber(output, shared);
				Codec.writeBytes(output, Arrays.copyOfRange(slot.key(), shared, slot.key().length));
				Codec.writeNullable(output, slot.value(), Codec::writeBytes);
				previous = slot.key();
			}
		}));
	}

	static Page decode(final byte[] sealed) throws IOException {
		return Codec.decode(Codec.unseal(sealed), input -> {
			final byte[][] keys = new byte[Codec.readCount(input, SMALLEST_ENTRY)][];
			final byte[][] values = new byte[keys.length][];
			byte[] previous = new byte[0];

			for (int i = 0; i < keys.length; i++) {
				keys[i] = key(previous, Codec.readInt(input), Codec.readBytes(input));
				values[i] = Codec.readNullable(input, Codec::readBytes);

				if (i > 0 && Arrays.compareUnsigned(previous, keys[i]) >= 0) {
					throw new CorruptIndexException("The keys of a run page are out of order");
				}

				previous = keys[i];
			}

			return new Page(keys, values, sealed.length + (long) keys.length * ENTRY_OVERHEAD);
		});
	}

	private static byte[] key(final byte[] previous, final int shared, final byte[] rest) throws IOException {
		if (shared > previous.length) {
			throw new CorruptIndexException("A key in a run page shares more than the key before it holds");
		}

		final byte[] key = Arrays.copyOf(previous, shared + rest.length);

		System.arraycopy(rest, 0, key, shared, rest.length);
		return key;
	}

	/* The position of the first key at or after the given one */
	int find(final byte[] key) {
		final int found = Arrays.binarySearch(keys, key, Arrays::compareUnsigned);
		return found >= 0 ? found : -found - 1;
	}

	int size() {
		return keys.length;
	}

	Slot slot(final int position) {
		return new Slot(keys[position], values[position]);
	}
}
