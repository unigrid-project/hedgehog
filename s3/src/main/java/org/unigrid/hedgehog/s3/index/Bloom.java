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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/* A key that was added always tests positive, and any other key tests positive at a rate set by the bits spent per
   key. Each key sets its probes by double hashing one 64-bit hash, FNV-1a mixed with the MurmurHash3 finalizer. */
final class Bloom {
	private static final double LN2 = Math.log(2);
	private static final int MAX_PROBES = 30;
	private static final long MAX_BITS = 1L << 30;
	private static final long FNV_OFFSET = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;
	private static final long MIX_FIRST = 0xff51afd7ed558ccdL;
	private static final long MIX_SECOND = 0xc4ceb9fe1a85ec53L;
	private static final long LOW_HALF = 0xFFFFFFFFL;

	private final long[] words;
	private final int probes;

	private Bloom(final long[] words, final int probes) {
		this.words = words;
		this.probes = probes;
	}

	static Bloom forKeys(final long keys, final int bitsPerKey) {
		final long bits = Math.clamp(keys * bitsPerKey, Long.SIZE, MAX_BITS);
		return new Bloom(new long[(int) ((bits + Long.SIZE - 1) / Long.SIZE)],
			Math.clamp(Math.round(bitsPerKey * LN2), 1, MAX_PROBES));
	}

	void add(final byte[] key) {
		final long hash = hash(key);

		for (int probe = 0; probe < probes; probe++) {
			final long bit = bit(hash, probe);

			words[(int) (bit / Long.SIZE)] |= 1L << bit;
		}
	}

	boolean mightContain(final byte[] key) {
		final long hash = hash(key);

		for (int probe = 0; probe < probes; probe++) {
			final long bit = bit(hash, probe);

			if ((words[(int) (bit / Long.SIZE)] & 1L << bit) == 0) {
				return false;
			}
		}

		return true;
	}

	private long bit(final long hash, final int probe) {
		return Math.floorMod((hash & LOW_HALF) + probe * (hash >>> Integer.SIZE), (long) words.length * Long.SIZE);
	}

	private static long hash(final byte[] key) {
		long hash = FNV_OFFSET;

		for (byte value : key) {
			hash = (hash ^ (value & 0xFF)) * FNV_PRIME;
		}

		hash = (hash ^ hash >>> 33) * MIX_FIRST;
		hash = (hash ^ hash >>> 33) * MIX_SECOND;
		return hash ^ hash >>> 33;
	}

	void write(final DataOutput output) throws IOException {
		output.writeByte(probes);
		Codec.writeNumber(output, words.length);

		for (long word : words) {
			output.writeLong(word);
		}
	}

	static Bloom read(final DataInput input) throws IOException {
		final int probes = input.readUnsignedByte();
		final long[] words = new long[Codec.readCount(input)];

		for (int i = 0; i < words.length; i++) {
			words[i] = input.readLong();
		}

		return new Bloom(words, probes);
	}

	long weight() {
		return (long) words.length * Long.BYTES;
	}
}
