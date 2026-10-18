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

import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/* The decoded index of a run: the first key, offset and length of every page, and the bloom filter of its keys */
record RunIndex(byte[][] firsts, long[] offsets, int[] lengths, Bloom bloom) implements PageCache.Weighed {
	private static final int FORMAT = 1;
	private static final int PAGE_OVERHEAD = 64;

	static RunIndex decode(final byte[] sealed) throws IOException {
		return Codec.decode(Codec.unseal(sealed), input -> {
			final int format = input.readUnsignedByte();

			if (format != FORMAT) {
				throw new IOException("Unknown run format " + format);
			}

			final int pages = Codec.readCount(input);
			final byte[][] firsts = new byte[pages][];
			final long[] offsets = new long[pages];
			final int[] lengths = new int[pages];

			for (int i = 0; i < pages; i++) {
				offsets[i] = Codec.readNumber(input);
				lengths[i] = Codec.readCount(input);
				firsts[i] = Codec.readBytes(input);
			}

			return new RunIndex(firsts, offsets, lengths, Bloom.read(input));
		});
	}

	void write(final DataOutput output) throws IOException {
		output.writeByte(FORMAT);
		Codec.writeNumber(output, firsts.length);

		for (int i = 0; i < firsts.length; i++) {
			Codec.writeNumber(output, offsets[i]);
			Codec.writeNumber(output, lengths[i]);
			Codec.writeBytes(output, firsts[i]);
		}

		bloom.write(output);
	}

	/* The page a key belongs in: the last one starting at or before it, or the first page for a key before all */
	int page(final byte[] key) {
		final int found = Arrays.binarySearch(firsts, key, Arrays::compareUnsigned);
		return found >= 0 ? found : Math.max(0, -found - 2);
	}

	int pages() {
		return firsts.length;
	}

	@Override
	public long weight() {
		return bloom.weight() + Arrays.stream(firsts).mapToLong(first -> first.length + PAGE_OVERHEAD).sum();
	}
}
