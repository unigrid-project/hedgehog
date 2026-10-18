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

/* A decoded run page: its keys in order with their values, where a null value is a tombstone */
record Page(byte[][] keys, byte[][] values, long weight) implements PageCache.Weighed {
	private static final int ENTRY_OVERHEAD = 48;

	static Page decode(final byte[] sealed) throws IOException {
		return Codec.decode(Codec.unseal(sealed), input -> {
			final byte[][] keys = new byte[Codec.readCount(input)][];
			final byte[][] values = new byte[keys.length][];
			byte[] previous = new byte[0];

			for (int i = 0; i < keys.length; i++) {
				final byte[] shared = Arrays.copyOf(previous, Codec.readCount(input));
				final byte[] rest = Codec.readBytes(input);

				keys[i] = Arrays.copyOf(shared, shared.length + rest.length);
				System.arraycopy(rest, 0, keys[i], shared.length, rest.length);
				values[i] = Codec.readNullable(input, Codec::readBytes);
				previous = keys[i];
			}

			return new Page(keys, values, sealed.length + (long) keys.length * ENTRY_OVERHEAD);
		});
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
