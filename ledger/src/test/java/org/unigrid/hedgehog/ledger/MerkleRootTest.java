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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class MerkleRootTest {
	private static final byte LEAF = 0;
	private static final byte NODE = 1;

	private static byte[] leaf(byte[] data) {
		return Digests.sha512(new byte[] { LEAF }, data);
	}

	private static byte[] node(byte[] left, byte[] right) {
		return Digests.sha512(new byte[] { NODE }, left, right);
	}

	@Example
	public void shouldHashNothingToTheEmptyDigest() {
		assertThat(MerkleRoot.of(List.of()), equalTo(Digests.sha512()));
	}

	@Example
	public void shouldHashOneLeaf() {
		final byte[] a = { 1 };

		assertThat(MerkleRoot.of(List.of(a)), equalTo(leaf(a)));
	}

	@Example
	public void shouldPairTwoLeaves() {
		final byte[] a = { 1 };
		final byte[] b = { 2 };

		assertThat(MerkleRoot.of(List.of(a, b)), equalTo(node(leaf(a), leaf(b))));
	}

	/* RFC 6962 splits at the largest power of two below the count, so a lone third leaf is not duplicated */
	@Example
	public void shouldNotDuplicateAnOddLeaf() {
		final byte[] a = { 1 };
		final byte[] b = { 2 };
		final byte[] c = { 3 };

		assertThat(MerkleRoot.of(List.of(a, b, c)), equalTo(node(node(leaf(a), leaf(b)), leaf(c))));
		assertThat(MerkleRoot.of(List.of(a, b, c)), not(equalTo(MerkleRoot.of(List.of(a, b, c, c)))));
	}

	@Property(tries = 100)
	public void shouldDependOnOrderAndEveryLeaf(@ForAll @Size(min = 2, max = 9) List<@Size(8) byte[]> leaves) {
		Assume.that(!Arrays.equals(leaves.get(0), leaves.get(leaves.size() - 1)));

		final byte[] root = MerkleRoot.of(leaves);
		final List<byte[]> swapped = new ArrayList<>(leaves);
		final List<byte[]> changed = new ArrayList<>(leaves);

		swapped.set(0, leaves.get(leaves.size() - 1));
		swapped.set(leaves.size() - 1, leaves.get(0));
		changed.set(0, new byte[] { 9, 9, 9 });

		assertThat(MerkleRoot.of(changed), not(equalTo(root)));
		assertThat(MerkleRoot.of(swapped), not(equalTo(root)));
		assertThat(MerkleRoot.of(leaves), equalTo(root));
		assertThat(root.length, equalTo(Digests.HASH_SIZE));
	}
}
