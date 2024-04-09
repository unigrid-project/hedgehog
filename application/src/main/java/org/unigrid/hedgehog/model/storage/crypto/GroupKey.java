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

package org.unigrid.hedgehog.model.storage.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.unigrid.hedgehog.model.storage.GroupId;

public final class GroupKey {
	public static final int PUBLIC_KEY_SIZE = Ed25519PublicKeyParameters.KEY_SIZE;
	public static final int SIGNATURE_SIZE = Ed25519PrivateKeyParameters.SIGNATURE_SIZE;
	public static final int SEED_SIZE = Ed25519PrivateKeyParameters.KEY_SIZE;
	private static final byte[] DELETE_CONTEXT = "hh-delete-v1".getBytes(StandardCharsets.US_ASCII);

	private final Ed25519PrivateKeyParameters privateKey;
	private final byte[] publicKey;
	private final GroupId groupId;

	public GroupKey(byte[] seed) {
		if (seed.length != SEED_SIZE) {
			throw new IllegalArgumentException("A group key seed is exactly " + SEED_SIZE + " bytes");
		}

		privateKey = new Ed25519PrivateKeyParameters(seed, 0);
		publicKey = privateKey.generatePublicKey().getEncoded();
		groupId = groupIdOf(publicKey);
	}

	public byte[] publicKey() {
		return publicKey.clone();
	}

	public GroupId groupId() {
		return groupId;
	}

	public byte[] sign(byte[] message) {
		final Ed25519Signer signer = new Ed25519Signer();

		signer.init(true, privateKey);
		signer.update(message, 0, message.length);
		return signer.generateSignature();
	}

	public byte[] signDelete(long timestamp) {
		return sign(deleteMessage(groupId(), timestamp));
	}

	public static GroupId groupIdOf(byte[] publicKey) {
		return GroupId.of(Hashes.sha256(publicKey));
	}

	public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
		if (publicKey.length != PUBLIC_KEY_SIZE || signature.length != SIGNATURE_SIZE) {
			return false;
		}

		try {
			final Ed25519Signer verifier = new Ed25519Signer();

			verifier.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
			verifier.update(message, 0, message.length);
			return verifier.verifySignature(signature);
		} catch (IllegalArgumentException ex) {
			/* Newer BouncyCastle releases reject a public key that is not a valid curve point */
			return false;
		}
	}

	public static byte[] deleteMessage(GroupId groupId, long timestamp) {
		return ByteBuffer.allocate(DELETE_CONTEXT.length + GroupId.SIZE + Long.BYTES)
			.put(DELETE_CONTEXT).put(groupId.bytes()).putLong(timestamp).array();
	}
}
