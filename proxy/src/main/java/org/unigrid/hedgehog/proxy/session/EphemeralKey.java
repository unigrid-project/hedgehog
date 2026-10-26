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

package org.unigrid.hedgehog.proxy.session;

import java.security.SecureRandom;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

/* An X25519 key pair that lives for one handshake */
public final class EphemeralKey {
	public static final int SIZE = X25519PrivateKeyParameters.KEY_SIZE;

	private final X25519PrivateKeyParameters privateKey;

	public EphemeralKey(byte[] privateKey) {
		this(parameters(privateKey));
	}

	private EphemeralKey(X25519PrivateKeyParameters privateKey) {
		this.privateKey = privateKey;
	}

	public static EphemeralKey generate(SecureRandom random) {
		return new EphemeralKey(new X25519PrivateKeyParameters(random));
	}

	public byte[] publicKey() {
		return privateKey.generatePublicKey().getEncoded();
	}

	public byte[] agree(byte[] peerPublicKey) throws HandshakeException {
		if (peerPublicKey.length != SIZE) {
			throw new HandshakeException("An X25519 public key is exactly " + SIZE + " bytes, found "
				+ peerPublicKey.length);
		}

		final X25519Agreement agreement = new X25519Agreement();
		final byte[] secret = new byte[agreement.getAgreementSize()];

		agreement.init(privateKey);

		try {
			agreement.calculateAgreement(new X25519PublicKeyParameters(peerPublicKey, 0), secret, 0);
		} catch (IllegalStateException ex) {
			/* BouncyCastle refuses the all-zero secret that every point of low order produces */
			throw new HandshakeException("The peer key is a point of low order");
		}

		return secret;
	}

	private static X25519PrivateKeyParameters parameters(byte[] privateKey) {
		if (privateKey.length != SIZE) {
			throw new IllegalArgumentException("An X25519 private key is exactly " + SIZE + " bytes, found "
				+ privateKey.length);
		}

		return new X25519PrivateKeyParameters(privateKey, 0);
	}
}
