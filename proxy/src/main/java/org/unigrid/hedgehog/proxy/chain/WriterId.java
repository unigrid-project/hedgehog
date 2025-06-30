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

package org.unigrid.hedgehog.proxy.chain;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

public final class WriterId extends FixedBytes implements Comparable<WriterId> {
	public static final int SIZE = 32;

	public WriterId(byte[] bytes) {
		super(bytes, SIZE);
	}

	public boolean verify(byte[] message, byte[] signature) {
		if (signature.length != Entry.SIGNATURE_SIZE || !isPrimeOrderPoint()) {
			return false;
		}

		final Ed25519Signer verifier = new Ed25519Signer();

		verifier.init(false, new Ed25519PublicKeyParameters(bytes(), 0));
		verifier.update(message, 0, message.length);
		return verifier.verifySignature(signature);
	}

	/* A writer id is untrusted input and a small order point verifies one signature for every message */
	private boolean isPrimeOrderPoint() {
		return Ed25519.validatePublicKeyFull(bytes(), 0);
	}

	@Override
	public int compareTo(WriterId other) {
		return compareBytes(other);
	}
}
