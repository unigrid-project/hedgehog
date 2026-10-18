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

package org.unigrid.hedgehog.model.bootstrap;

/*
   Millions of block hashes have to be looked up while linking the chain. A HashMap keyed on wrapped
   byte arrays costs several hundred megabytes in object headers alone, so the hashes stay in one flat
   array and this open-addressed table stores nothing but their positions in it.
*/
public final class BlockHashIndex {
	public static final int NOT_FOUND = -1;

	private static final double LOAD_FACTOR = 0.6;

	private final byte[] hashes;
	private final int[] slots;
	private final int mask;

	public BlockHashIndex(byte[] hashes, int capacity) {
		this.hashes = hashes;
		this.slots = new int[tableSize(capacity)];
		this.mask = slots.length - 1;
	}

	public void put(int position) {
		int slot = slotOf(hashes, position * Hashing.HASH_SIZE);

		while (slots[slot] != 0) {
			slot = (slot + 1) & mask;
		}

		slots[slot] = position + 1;
	}

	public int get(byte[] hash, int hashOffset) {
		int slot = slotOf(hash, hashOffset);

		while (slots[slot] != 0) {
			final int position = slots[slot] - 1;

			if (matches(hash, hashOffset, position)) {
				return position;
			}

			slot = (slot + 1) & mask;
		}

		return NOT_FOUND;
	}

	private boolean matches(byte[] hash, int hashOffset, int position) {
		final int base = position * Hashing.HASH_SIZE;

		for (int i = 0; i < Hashing.HASH_SIZE; i++) {
			if (hashes[base + i] != hash[hashOffset + i]) {
				return false;
			}
		}

		return true;
	}

	private int slotOf(byte[] hash, int hashOffset) {
		long value = 0;

		for (int i = 0; i < Long.BYTES; i++) {
			value = (value << Byte.SIZE) | Byte.toUnsignedLong(hash[hashOffset + i]);
		}

		return (int) (value ^ (value >>> Integer.SIZE)) & mask;
	}

	private static int tableSize(int capacity) {
		return Integer.highestOneBit(Math.max(16, (int) (capacity / LOAD_FACTOR))) << 1;
	}
}
