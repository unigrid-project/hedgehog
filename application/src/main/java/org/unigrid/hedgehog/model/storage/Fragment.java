/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation, UGD Software AB

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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;
import org.unigrid.hedgehog.model.storage.crypto.MerkleTree;

@Value
public class Fragment {
	private static final int INDEX_AND_PROOF_LENGTH = 2;

	private final GroupDescriptor descriptor;
	private final int index;

	/* Determined by the descriptor, index and data, and a list of arrays would only compare by reference */
	@EqualsAndHashCode.Exclude @ToString.Exclude private final List<byte[]> proof;
	@ToString.Exclude private final byte[] data;

	public GroupId groupId() {
		return descriptor.groupId();
	}

	public StorageFormat format() {
		return descriptor.getFormat();
	}

	public boolean isExtra() {
		return index >= descriptor.guaranteedFragments();
	}

	public boolean verify(final GroupId expected) {
		return descriptor.isWellFormed() && descriptor.groupId().equals(expected)
			&& data.length == descriptor.getFragmentSize() && descriptor.isValid()
			&& MerkleTree.verify(descriptor.getMerkleRoot(), descriptor.getMaxFragments(), index, data, proof);
	}

	public byte[] encode() {
		final ByteBuffer buffer = ByteBuffer.allocate(GroupDescriptor.ENCODED_SIZE + INDEX_AND_PROOF_LENGTH
			+ proof.size() * MerkleTree.HASH_SIZE + Integer.BYTES + data.length);

		buffer.put(descriptor.encode()).put((byte) index).put((byte) proof.size());
		proof.forEach(buffer::put);
		return buffer.putInt(data.length).put(data).array();
	}

	/* The data length must equal what remains, so a forged length can never drive an allocation beyond the input */
	public static Fragment decode(final byte[] encoded) {
		final ByteBuffer buffer = ByteBuffer.wrap(encoded);
		final GroupDescriptor descriptor = GroupDescriptor.decode(buffer);
		final int index = buffer.get() & 0xFF;
		final int proofLength = buffer.get() & 0xFF;
		final List<byte[]> proof = IntStream.range(0, proofLength)
			.mapToObj(i -> GroupDescriptor.read(buffer, MerkleTree.HASH_SIZE)).collect(Collectors.toList());
		final int length = buffer.getInt();

		if (length != descriptor.getFragmentSize() || length != buffer.remaining()) {
			throw new IllegalArgumentException("Fragment length does not match its descriptor");
		}

		return new Fragment(descriptor, index, proof, GroupDescriptor.read(buffer, length));
	}
}
