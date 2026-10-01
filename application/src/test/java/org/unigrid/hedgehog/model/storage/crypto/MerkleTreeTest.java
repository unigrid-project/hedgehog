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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class MerkleTreeTest {
	private static List<byte[]> fragments(int count, long seed) {
		final Random random = new Random(seed);
		final List<byte[]> fragments = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			final byte[] fragment = new byte[32];
			random.nextBytes(fragment);
			fragments.add(fragment);
		}

		return fragments;
	}

	@Property(tries = 100)
	public void everySlotProves(@ForAll @IntRange(min = 1, max = 255) int count, @ForAll long seed) {
		final List<byte[]> fragments = fragments(count, seed);
		final MerkleTree tree = MerkleTree.of(fragments);

		for (int i = 0; i < count; i++) {
			assertThat(MerkleTree.verify(tree.root(), count, i, fragments.get(i), tree.proof(i)), is(true));
		}
	}

	@Property(tries = 100)
	public void rejectsChangedBytesAndWrongIndices(@ForAll @IntRange(min = 2, max = 64) int count, @ForAll long seed,
		@ForAll @IntRange(min = 0, max = 63) int pick) {

		final List<byte[]> fragments = fragments(count, seed);
		final MerkleTree tree = MerkleTree.of(fragments);
		final int index = pick % count;
		final byte[] changed = fragments.get(index).clone();
		changed[0]++;

		assertThat(MerkleTree.verify(tree.root(), count, index, changed, tree.proof(index)), is(false));
		assertThat(MerkleTree.verify(tree.root(), count, (index + 1) % count, fragments.get(index), tree.proof(index)),
			is(false)
		);
	}

	@Property(tries = 50)
	public void rejectsProofsOfTheWrongLength(@ForAll @IntRange(min = 2, max = 64) int count, @ForAll long seed) {
		final List<byte[]> fragments = fragments(count, seed);
		final MerkleTree tree = MerkleTree.of(fragments);
		final List<byte[]> shortProof = tree.proof(0).subList(1, tree.proof(0).size());

		assertThat(MerkleTree.verify(tree.root(), count, 0, fragments.get(0), shortProof), is(false));
	}
}
