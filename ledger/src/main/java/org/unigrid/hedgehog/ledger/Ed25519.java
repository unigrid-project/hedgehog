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

package org.unigrid.hedgehog.ledger;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

public final class Ed25519 {
	public static final int SEED_SIZE = Ed25519PrivateKeyParameters.KEY_SIZE;
	public static final int SIGNATURE_SIZE = Ed25519PrivateKeyParameters.SIGNATURE_SIZE;

	private Ed25519() {
		/* Static helpers only */
	}

	public static AccountKey publicKey(byte[] seed) {
		return new AccountKey(privateKey(seed).generatePublicKey().getEncoded());
	}

	public static byte[] sign(byte[] seed, byte[] message) {
		final Ed25519Signer signer = new Ed25519Signer();

		signer.init(true, privateKey(seed));
		signer.update(message, 0, message.length);
		return signer.generateSignature();
	}

	public static boolean verify(AccountKey key, byte[] message, byte[] signature) {
		if (signature.length != SIGNATURE_SIZE) {
			return false;
		}

		final Ed25519Signer signer = new Ed25519Signer();

		signer.init(false, new Ed25519PublicKeyParameters(key.bytes(), 0));
		signer.update(message, 0, message.length);
		return signer.verifySignature(signature);
	}

	private static Ed25519PrivateKeyParameters privateKey(byte[] seed) {
		return new Ed25519PrivateKeyParameters(Bytes.requireSize(seed, SEED_SIZE, "An Ed25519 seed"), 0);
	}
}
