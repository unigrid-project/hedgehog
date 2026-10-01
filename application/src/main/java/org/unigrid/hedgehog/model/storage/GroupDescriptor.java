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

package org.unigrid.hedgehog.model.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import lombok.Builder;
import lombok.Value;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.crypto.MerkleTree;
import org.unigrid.hedgehog.model.storage.erasure.ReedSolomon;

@Value
@Builder(toBuilder = true)
public class GroupDescriptor {
	private static final int HEADER_SIZE = 4 + Integer.BYTES;
	public static final int ENCODED_SIZE = HEADER_SIZE + GroupKey.PUBLIC_KEY_SIZE + MerkleTree.HASH_SIZE
		+ GroupKey.SIGNATURE_SIZE;

	private final StorageFormat format;
	private final int dataFragments;
	private final int parityFragments;
	private final int maxFragments;
	private final int fragmentSize;
	private final byte[] publicKey;
	private final byte[] merkleRoot;
	private final byte[] signature;

	/* Validating first keeps a count above 255 from being silently truncated into the byte that gets signed */
	public static GroupDescriptor sign(final GroupKey key, final StorageFormat format, final LayoutParameters layout,
		final byte[] merkleRoot) {

		layout.validate();
		LayoutParameters.require(merkleRoot.length == MerkleTree.HASH_SIZE, "A Merkle root is exactly 32 bytes");

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
		try {
			return Math.multiplyExact(dataFragments, fragmentSize);
		} catch (ArithmeticException ex) {
			throw new IllegalArgumentException("The group describes a chunk beyond addressable size", ex);
		}
	}

	/* Each count is bounded before they are summed, so no signed combination of fields can overflow */
	public boolean isWellFormed() {
		return LayoutParameters.inRange(dataFragments, 1, ReedSolomon.MAX_SHARDS)
			&& LayoutParameters.inRange(parityFragments, 0, ReedSolomon.MAX_SHARDS)
			&& LayoutParameters.inRange(maxFragments, guaranteedFragments(), ReedSolomon.MAX_SHARDS)
			&& fragmentSize > 0;
	}

	public boolean isValid() {
		return GroupKey.verify(publicKey, signedBytes(), signature);
	}

	public byte[] encode() {
		return fields().put(signature).array();
	}

	public static GroupDescriptor decode(final ByteBuffer buffer) {
		return GroupDescriptor.builder().format(StorageFormat.of(buffer.get() & 0xFF))
			.dataFragments(buffer.get() & 0xFF).parityFragments(buffer.get() & 0xFF)
			.maxFragments(buffer.get() & 0xFF).fragmentSize(buffer.getInt())
			.publicKey(read(buffer, GroupKey.PUBLIC_KEY_SIZE)).merkleRoot(read(buffer, MerkleTree.HASH_SIZE))
			.signature(read(buffer, GroupKey.SIGNATURE_SIZE)).build();
	}

	static byte[] read(final ByteBuffer buffer, final int length) {
		final byte[] bytes = new byte[length];
		buffer.get(bytes);
		return bytes;
	}

	private ByteBuffer fields() {
		return ByteBuffer.allocate(ENCODED_SIZE).put(format.getId()).put((byte) dataFragments)
			.put((byte) parityFragments).put((byte) maxFragments).putInt(fragmentSize).put(publicKey)
			.put(merkleRoot);
	}

	byte[] signedBytes() {
		final byte[] context = ("hh-group-v" + (format.getId() & 0xFF)).getBytes(StandardCharsets.US_ASCII);
		final byte[] fields = Arrays.copyOf(fields().array(), ENCODED_SIZE - GroupKey.SIGNATURE_SIZE);

		return ByteBuffer.allocate(context.length + fields.length).put(context).put(fields).array();
	}
}
