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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import lombok.Builder;
import lombok.Value;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.crypto.MerkleTree;

@Value
@Builder(toBuilder = true)
public class GroupDescriptor {
	private static final int HEADER_SIZE = 4 + Integer.BYTES;
	public static final int ENCODED_SIZE = HEADER_SIZE + GroupKey.PUBLIC_KEY_SIZE + MerkleTree.HASH_SIZE
		+ GroupKey.SIGNATURE_SIZE;
	private static final byte[] CONTEXT = "hh-group".getBytes(StandardCharsets.US_ASCII);

	private final StorageFormat format;
	private final int dataFragments;
	private final int parityFragments;
	private final int maxFragments;
	private final int fragmentSize;
	private final byte[] publicKey;
	private final byte[] merkleRoot;
	private final byte[] signature;

	public static GroupDescriptor sign(GroupKey key, StorageFormat format, LayoutParameters layout, byte[] merkleRoot) {
		final GroupDescriptor unsigned = GroupDescriptor.builder().format(format)
			.dataFragments(layout.dataFragments()).parityFragments(layout.parityFragments())
			.maxFragments(layout.maxFragments()).fragmentSize(layout.getFragmentSize())
			.publicKey(key.publicKey()).merkleRoot(merkleRoot).signature(new byte[GroupKey.SIGNATURE_SIZE])
			.build();

		return unsigned.toBuilder().signature(key.sign(unsigned.signedBytes())).build();
	}

	public GroupId groupId() {
		return GroupKey.groupIdOf(publicKey);
	}

	public int guaranteedFragments() {
		return dataFragments + parityFragments;
	}

	public int chunkSize() {
		return dataFragments * fragmentSize;
	}

	public boolean isWellFormed() {
		return dataFragments >= 1 && guaranteedFragments() <= maxFragments && fragmentSize > 0;
	}

	public boolean isValid() {
		return GroupKey.verify(publicKey, signedBytes(), signature);
	}

	public byte[] encode() {
		return fields().put(signature).array();
	}

	public static GroupDescriptor decode(ByteBuffer buffer) {
		return GroupDescriptor.builder().format(StorageFormat.of(buffer.get() & 0xFF))
			.dataFragments(buffer.get() & 0xFF).parityFragments(buffer.get() & 0xFF)
			.maxFragments(buffer.get() & 0xFF).fragmentSize(buffer.getInt())
			.publicKey(read(buffer, GroupKey.PUBLIC_KEY_SIZE)).merkleRoot(read(buffer, MerkleTree.HASH_SIZE))
			.signature(read(buffer, GroupKey.SIGNATURE_SIZE)).build();
	}

	static byte[] read(ByteBuffer buffer, int length) {
		final byte[] bytes = new byte[length];
		buffer.get(bytes);
		return bytes;
	}

	private ByteBuffer fields() {
		return ByteBuffer.allocate(ENCODED_SIZE).put(format.getId()).put((byte) dataFragments)
			.put((byte) parityFragments).put((byte) maxFragments).putInt(fragmentSize).put(publicKey)
			.put(merkleRoot);
	}

	private byte[] signedBytes() {
		final byte[] fields = Arrays.copyOf(fields().array(), ENCODED_SIZE - GroupKey.SIGNATURE_SIZE);
		return ByteBuffer.allocate(CONTEXT.length + fields.length).put(CONTEXT).put(fields).array();
	}
}
