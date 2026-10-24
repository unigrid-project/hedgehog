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

package org.unigrid.hedgehog.model.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;

/**
* The signatures that verified, each known by its key, the SHA-512 digest of what it signs and its bytes. A check
* answers the same however often it is made, and a genuine signature reaches a node from every peer that sends what it
* signs, in copies of other bytes around it, so one that verified is never checked again: the head of a version, its
* co-signature and the approvals of a change of the board keys alike. A signature that did not verify is not
* remembered, as anyone can make another one of other bytes, which would only push out those in use. So many are
* remembered and no more, the one used least recently going first. Only the checks against board keys are remembered:
* anyone can make a key and signatures with it that verify, as the announcements of gridnodes hold, and those are
* checked without remembering them ({@link Signature#isSignedBy}), so nothing that anyone else sends pushes one out.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class VerifiedSignatures {
	/* Every approval of the longest chain of board keys, and the heads of every type, many times over */
	static final int CAPACITY = 1 << 13;

	private static final float LOAD_FACTOR = 0.75f;
	private static final Map<ByteBuffer, Boolean> VERIFIED = Collections.synchronizedMap(
		new LinkedHashMap<>(CAPACITY, LOAD_FACTOR, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<ByteBuffer, Boolean> eldest) {
				return size() > CAPACITY;
			}
		}
	);

	/* A key is known in its one spelling only (Signature.isPublicKey): a number reads digits of other scripts as
	   theirs, which in bytes of US-ASCII would all read as one key */
	static boolean isKnown(String key, byte[] digest, byte[] signature) {
		return Objects.nonNull(signature) && Signature.isPublicKey(key)
			&& Objects.nonNull(VERIFIED.get(idOf(key, digest, signature)));
	}

	static void remember(String key, byte[] digest, byte[] signature) {
		if (Signature.isPublicKey(key)) {
			VERIFIED.put(idOf(key, digest, signature), Boolean.TRUE);
		}
	}

	/* A process that starts over has checked nothing, as each test of a process has not */
	static void forget() {
		VERIFIED.clear();
	}

	/* Each part with its length, so that no two of them read as other parts */
	private static ByteBuffer idOf(String key, byte[] digest, byte[] signature) {
		final MessageDigest id = DigestUtils.getSha512Digest();

		for (byte[] part : new byte[][] { key.getBytes(StandardCharsets.US_ASCII), digest, signature }) {
			id.update(ByteBuffer.allocate(Integer.BYTES).putInt(part.length).array());
			id.update(part);
		}

		return ByteBuffer.wrap(id.digest());
	}
}
