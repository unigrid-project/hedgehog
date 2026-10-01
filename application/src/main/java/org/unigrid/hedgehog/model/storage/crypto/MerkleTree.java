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

package org.unigrid.hedgehog.model.storage.crypto;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class MerkleTree {
	public static final int HASH_SIZE = 32;
	private static final byte LEAF = 0x00;
	private static final byte NODE = 0x01;
	private static final byte EMPTY = 0x02;

	private final List<byte[][]> levels;

	private MerkleTree(List<byte[][]> levels) {
		this.levels = levels;
	}

	public static MerkleTree of(List<byte[]> fragments) {
		final byte[][] leaves = new byte[1 << depth(fragments.size())][];
		final byte[] emptyLeaf = Hashes.sha256(new byte[] { EMPTY });

		for (int i = 0; i < leaves.length; i++) {
			leaves[i] = i < fragments.size() ? leafHash(i, fragments.get(i)) : emptyLeaf;
		}

		final List<byte[][]> levels = new ArrayList<>(List.<byte[][]>of(leaves));

		while (levels.get(levels.size() - 1).length > 1) {
			levels.add(parentsOf(levels.get(levels.size() - 1)));
		}

		return new MerkleTree(levels);
	}

	public static int depth(int leafCount) {
		return Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, leafCount) - 1);
	}

	public byte[] root() {
		return levels.get(levels.size() - 1)[0].clone();
	}

	public List<byte[]> proof(int index) {
		final List<byte[]> proof = new ArrayList<>();
		int position = index;

		for (int level = 0; level < levels.size() - 1; level++) {
			proof.add(levels.get(level)[position ^ 1].clone());
			position >>= 1;
		}

		return proof;
	}

	public static boolean verify(byte[] root, int leafCount, int index, byte[] fragment, List<byte[]> proof) {
		if (index < 0 || index >= leafCount || proof.size() != depth(leafCount)) {
			return false;
		}

		byte[] hash = leafHash(index, fragment);
		int position = index;

		for (byte[] sibling : proof) {
			hash = (position & 1) == 0 ? nodeHash(hash, sibling) : nodeHash(sibling, hash);
			position >>= 1;
		}

		return MessageDigest.isEqual(hash, root);
	}

	private static byte[][] parentsOf(byte[][] children) {
		final byte[][] parents = new byte[children.length / 2][];

		for (int i = 0; i < parents.length; i++) {
			parents[i] = nodeHash(children[2 * i], children[2 * i + 1]);
		}

		return parents;
	}

	private static byte[] leafHash(int index, byte[] fragment) {
		return Hashes.sha256(new byte[] { LEAF, (byte) index }, fragment);
	}

	private static byte[] nodeHash(byte[] left, byte[] right) {
		return Hashes.sha256(new byte[] { NODE }, left, right);
	}
}
