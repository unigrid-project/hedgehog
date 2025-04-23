/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

/* The tree shape of RFC 6962 with SHA-512. A lone leaf is never duplicated, and the 0x00 and 0x01
   prefixes keep a leaf from ever being mistaken for an inner node. */
public final class MerkleRoot {
	private static final byte[] LEAF = { 0 };
	private static final byte[] NODE = { 1 };

	private MerkleRoot() {
		/* Static helpers only */
	}

	public static byte[] of(List<byte[]> leaves) {
		return leaves.isEmpty() ? Digests.sha512() : subtree(leaves, 0, leaves.size());
	}

	private static byte[] subtree(List<byte[]> leaves, int from, int to) {
		if (to - from == 1) {
			return Digests.sha512(LEAF, leaves.get(from));
		}

		final int split = from + Integer.highestOneBit(to - from - 1);

		return Digests.sha512(NODE, subtree(leaves, from, split), subtree(leaves, split, to));
	}
}
