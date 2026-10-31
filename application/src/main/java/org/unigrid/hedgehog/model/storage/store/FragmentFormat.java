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

package org.unigrid.hedgehog.model.storage.store;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.GroupKey;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.Holding;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.Tier;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.Tombstone;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class FragmentFormat {
	static final int HEADER_SIZE = 4 + Long.BYTES;
	static final int TOMBSTONE_SIZE = 2 * Long.BYTES + GroupKey.PUBLIC_KEY_SIZE + GroupKey.SIGNATURE_SIZE;
	static final int UNSIGNED_BYTE_MAX = 0xFF;

	/* [format u8][slots u8][tier u8][index u8][sequence u64][payload] */
	static byte[] encodeFragment(final Holding holding, final byte[] payload) {
		return ByteBuffer.allocate(HEADER_SIZE + payload.length).put(holding.getFormat().getId())
			.put((byte) holding.getSlots()).put((byte) holding.getTier().ordinal())
			.put((byte) holding.getIndex()).putLong(holding.getSequence()).put(payload).array();
	}

	static Holding decodeHeader(final byte[] header, final long size) {
		final ByteBuffer buffer = ByteBuffer.wrap(header);

		return Holding.builder().format(StorageFormat.of(buffer.get() & UNSIGNED_BYTE_MAX))
			.slots(buffer.get() & UNSIGNED_BYTE_MAX).tier(Tier.values()[buffer.get()])
			.index(buffer.get() & UNSIGNED_BYTE_MAX).sequence(buffer.getLong()).size(size).build();
	}

	static byte[] payloadOf(final byte[] content) {
		return Arrays.copyOfRange(content, HEADER_SIZE, content.length);
	}

	static boolean isForeign(final byte[] header) {
		return header.length > 0 && StorageFormat.find(header[0] & UNSIGNED_BYTE_MAX).isEmpty();
	}

	/* [expiry u64][timestamp i64][public key 32 bytes][signature 64 bytes] */
	static byte[] encodeTombstone(final Tombstone tombstone) {
		final DeleteProof proof = tombstone.getProof();

		return ByteBuffer.allocate(TOMBSTONE_SIZE).putLong(tombstone.getExpiry().toEpochMilli())
			.putLong(proof.getTimestamp()).put(proof.getPublicKey()).put(proof.getSignature()).array();
	}

	static Tombstone decodeTombstone(final byte[] content) {
		if (content.length != TOMBSTONE_SIZE) {
			throw new IllegalArgumentException("A tombstone file is exactly " + TOMBSTONE_SIZE + " bytes");
		}

		final ByteBuffer buffer = ByteBuffer.wrap(content);
		final Instant expiry = Instant.ofEpochMilli(buffer.getLong());
		final long timestamp = buffer.getLong();
		final byte[] publicKey = new byte[GroupKey.PUBLIC_KEY_SIZE];
		final byte[] signature = new byte[GroupKey.SIGNATURE_SIZE];

		buffer.get(publicKey).get(signature);
		return new Tombstone(expiry, new DeleteProof(publicKey, timestamp, signature));
	}
}
