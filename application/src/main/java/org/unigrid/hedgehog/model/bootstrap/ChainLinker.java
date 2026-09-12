/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;

/*
   Roughly one block in twenty-three stored by the legacy daemon is on a branch that lost. Balances are
   only correct if those are dropped, so the chain is rebuilt here: every stored block is linked to its
   parent by hash, heights are assigned forwards from the genesis block, and the walk back from the
   deepest block is the only thing that survives.
*/
@Slf4j
public final class ChainLinker {
	private final BlockFileStore store;
	private final int count;
	private final byte[] hashes;
	private final byte[] previousHashes;
	private final int[] files;
	private final int[] offsets;
	private final int[] lengths;
	private final int[] times;
	private final int[] parents;

	private ChainLinker(BlockFileStore store, int count) {
		this.store = store;
		this.count = count;
		this.hashes = new byte[count * Hashing.HASH_SIZE];
		this.previousHashes = new byte[count * Hashing.HASH_SIZE];
		this.files = new int[count];
		this.offsets = new int[count];
		this.lengths = new int[count];
		this.times = new int[count];
		this.parents = new int[count];
	}

	public static Chain link(BlockFileStore store) {
		final int[] stored = {0};

		store.forEachBlock(location -> stored[0]++);
		log.info("Indexing {} stored blocks", stored[0]);

		final ChainLinker linker = new ChainLinker(store, stored[0]);

		linker.readHeaders();
		linker.resolveParents();
		return linker.buildChain(linker.assignHeights());
	}

	private void readHeaders() {
		final int[] next = {0};

		store.forEachBlock(location -> {
			final int position = next[0]++;
			final BlockHeader header = BlockParser.header(store.read(location));

			System.arraycopy(header.getHash(), 0, hashes, position * Hashing.HASH_SIZE, Hashing.HASH_SIZE);
			System.arraycopy(header.getPreviousHash(), 0, previousHashes,
				position * Hashing.HASH_SIZE, Hashing.HASH_SIZE);
			files[position] = location.getFile();
			offsets[position] = location.getOffset();
			lengths[position] = location.getLength();
			times[position] = header.getTime();
		});
	}

	private void resolveParents() {
		final BlockHashIndex index = new BlockHashIndex(hashes, count);

		for (int i = 0; i < count; i++) {
			index.put(i);
		}

		for (int i = 0; i < count; i++) {
			parents[i] = index.get(previousHashes, i * Hashing.HASH_SIZE);
		}
	}

	private int[] assignHeights() {
		final int[] heights = new int[count];
		final int[] childStart = childOffsets();
		final int[] children = childList(childStart);
		final int[] pending = new int[count];
		int top = 0;

		Arrays.fill(heights, -1);

		for (int i = 0; i < count; i++) {
			if (parents[i] == BlockHashIndex.NOT_FOUND) {
				heights[i] = 0;
				pending[top++] = i;
			}
		}

		while (top > 0) {
			final int block = pending[--top];

			for (int i = childStart[block]; i < childStart[block + 1]; i++) {
				heights[children[i]] = heights[block] + 1;
				pending[top++] = children[i];
			}
		}

		return heights;
	}

	private int[] childOffsets() {
		final int[] starts = new int[count + 1];

		for (int i = 0; i < count; i++) {
			if (parents[i] != BlockHashIndex.NOT_FOUND) {
				starts[parents[i] + 1]++;
			}
		}

		for (int i = 0; i < count; i++) {
			starts[i + 1] += starts[i];
		}

		return starts;
	}

	private int[] childList(int[] childStart) {
		final int[] cursor = Arrays.copyOf(childStart, count);
		final int[] children = new int[childStart[count]];

		for (int i = 0; i < count; i++) {
			if (parents[i] != BlockHashIndex.NOT_FOUND) {
				children[cursor[parents[i]]++] = i;
			}
		}

		return children;
	}

	private Chain buildChain(int[] heights) {
		final int tip = deepest(heights);
		final int height = heights[tip];
		final int[] chainFiles = new int[height + 1];
		final int[] chainOffsets = new int[height + 1];
		final int[] chainLengths = new int[height + 1];
		final int[] chainTimes = new int[height + 1];

		for (int block = tip; block != BlockHashIndex.NOT_FOUND; block = parents[block]) {
			final int position = heights[block];

			chainFiles[position] = files[block];
			chainOffsets[position] = offsets[block];
			chainLengths[position] = lengths[block];
			chainTimes[position] = times[block];
		}

		log.info("Active chain has {} blocks, discarding {} stale blocks",
			height + 1, count - height - 1);

		return new Chain(chainFiles, chainOffsets, chainLengths, chainTimes,
			Arrays.copyOfRange(hashes, tip * Hashing.HASH_SIZE, (tip + 1) * Hashing.HASH_SIZE), count);
	}

	private int deepest(int[] heights) {
		int tip = 0;

		for (int i = 1; i < count; i++) {
			if (heights[i] > heights[tip]) {
				tip = i;
			}
		}

		if (heights[tip] < 0) {
			throw new IllegalStateException("No block could be linked back to the genesis block");
		}

		return tip;
	}
}
