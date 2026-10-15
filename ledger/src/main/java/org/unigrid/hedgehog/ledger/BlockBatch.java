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

package org.unigrid.hedgehog.ledger;

import java.util.List;
import java.util.stream.IntStream;

/* The answer to a block request: blocks that follow one another, each one height above the one before. The byte
   bound is of the encoded blocks together. It is no less than MAX_BLOCKS of the largest block there can be, so it
   never refuses a batch of legal blocks; it is the cap of what a reader takes in for one batch, and what a sender
   stops at, should the largest block ever grow. */
public record BlockBatch(List<Block> blocks) {
	public static final int MAX_BLOCKS = 16;
	public static final int MAX_BYTES = 8 * 1024 * 1024;

	public BlockBatch {
		blocks = List.copyOf(blocks);

		if (blocks.size() > MAX_BLOCKS) {
			throw new IllegalArgumentException("A batch holds at most " + MAX_BLOCKS + " blocks, found "
				+ blocks.size());
		}

		if (!consecutive(blocks)) {
			throw new IllegalArgumentException("The blocks of a batch are of consecutive heights");
		}
	}

	private static boolean consecutive(List<Block> blocks) {
		return IntStream.range(1, blocks.size())
			.allMatch(i -> blocks.get(i).getHeight() == blocks.get(i - 1).getHeight() + 1);
	}
}
