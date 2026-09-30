/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.storage;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.crypto.MerkleTree;
import org.unigrid.hedgehog.model.storage.erasure.ReedSolomon;

public final class ChunkGroups {
	private ChunkGroups() {
		/* Static helpers only */
	}

	public static List<Fragment> seal(final byte[] chunk, final GroupKey key, final StorageFormat format,
		final LayoutParameters layout) {

		layout.validate();
		LayoutParameters.require(chunk.length == layout.getChunkSize(), "Chunk size does not match the layout");

		final int dataFragments = layout.dataFragments();
		final byte[][] data = IntStream.range(0, dataFragments).mapToObj(i -> Arrays.copyOfRange(chunk,
			i * layout.getFragmentSize(), (i + 1) * layout.getFragmentSize())).toArray(byte[][]::new);
		final byte[][] shards = withParity(data, layout.maxFragments() - dataFragments);
		final MerkleTree tree = MerkleTree.of(Arrays.asList(shards));
		final GroupDescriptor descriptor = GroupDescriptor.sign(key, format, layout, tree.root());

		return fragmentsOf(descriptor, tree, shards, IntStream.range(0, shards.length).boxed()
			.collect(Collectors.toList()));
	}

	public static byte[] open(final Collection<Fragment> verified) {
		final GroupDescriptor descriptor = descriptorOf(verified);
		final byte[][] shards = decode(descriptor, verified);
		final ByteBuffer chunk = ByteBuffer.allocate(descriptor.chunkSize());

		IntStream.range(0, descriptor.getDataFragments()).forEach(i -> chunk.put(shards[i]));
		return chunk.array();
	}

	/* The rebuilt tree must match the signed root, so a repairer can never place fragments the owner did not sign. */
	public static List<Fragment> rebuild(final Collection<Fragment> verified, final Collection<Integer> indices) {
		final GroupDescriptor descriptor = descriptorOf(verified);

		LayoutParameters.require(indices.stream().allMatch(index -> isSlotOf(descriptor, index)),
			"Cannot rebuild a fragment outside the group"
		);

		final byte[][] shards = decode(descriptor, verified);
		final MerkleTree tree = MerkleTree.of(Arrays.asList(shards));

		if (!MessageDigest.isEqual(tree.root(), descriptor.getMerkleRoot())) {
			throw new IllegalStateException("Rebuilt fragments do not match the signed group");
		}

		return fragmentsOf(descriptor, tree, shards, indices);
	}

	private static boolean isSlotOf(final GroupDescriptor descriptor, final int index) {
		return LayoutParameters.inRange(index, 0, descriptor.getMaxFragments() - 1);
	}

	private static byte[][] withParity(final byte[][] data, final int parityShards) {
		final byte[][] parity = new ReedSolomon(data.length, parityShards).encode(data);
		final byte[][] shards = Arrays.copyOf(data, data.length + parityShards);

		System.arraycopy(parity, 0, shards, data.length, parityShards);
		return shards;
	}

	private static byte[][] decode(final GroupDescriptor descriptor, final Collection<Fragment> verified) {
		final int total = descriptor.getMaxFragments();
		final byte[][] shards = new byte[total][];
		final boolean[] present = new boolean[total];

		for (final Fragment fragment : verified) {
			shards[fragment.getIndex()] = fragment.getData();
			present[fragment.getIndex()] = true;
		}

		return new ReedSolomon(descriptor.getDataFragments(), total - descriptor.getDataFragments())
			.decode(shards, present);
	}

	/* Anyone can sign a group, so a validly signed descriptor still has to describe a codable one. And one key may
	   sign several seals over time, where mixing their fragments would decode to garbage. */
	private static GroupDescriptor descriptorOf(final Collection<Fragment> verified) {
		LayoutParameters.require(!verified.isEmpty(), "No fragments to work with");

		final GroupDescriptor descriptor = verified.iterator().next().getDescriptor();

		LayoutParameters.require(descriptor.isWellFormed(), "Fragments describe a malformed group");
		LayoutParameters.require(verified.stream().allMatch(fragment -> fragment.getDescriptor().equals(descriptor)),
			"Fragments belong to different seals"
		);
		LayoutParameters.require(verified.stream().allMatch(fragment -> isSlotOf(descriptor, fragment.getIndex())
			&& fragment.getData().length == descriptor.getFragmentSize()), "Fragments do not fit their group"
		);

		return descriptor;
	}

	private static List<Fragment> fragmentsOf(final GroupDescriptor descriptor, final MerkleTree tree,
		final byte[][] shards, final Collection<Integer> indices) {

		return indices.stream().map(i -> new Fragment(descriptor, i, tree.proof(i), shards[i]))
			.collect(Collectors.toList());
	}
}
