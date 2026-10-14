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

import java.math.BigInteger;
import java.util.Arrays;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.util.BigIntegers;

/* A key of the legacy chain with the secret `secret`, signing the way its wallet does. The recovery id in the
   header is not used by the ledger, which is handed the key, so it is left at 0. */
final class LegacyWallet {
	private static final int COORDINATE = 32;

	private final BigInteger secret;
	private final boolean compressed;
	private final byte[] publicKey;

	LegacyWallet(int secret, boolean compressed) {
		this.secret = BigInteger.valueOf(secret);
		this.compressed = compressed;
		this.publicKey = LegacySignature.CURVE.getG().multiply(this.secret).getEncoded(compressed);
	}

	byte[] publicKey() {
		return publicKey.clone();
	}

	LegacyAddress address() {
		return LegacySignature.addressOf(publicKey);
	}

	byte[] sign(String message) {
		final ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));

		signer.init(true, new ECPrivateKeyParameters(secret, LegacySignature.CURVE));

		final BigInteger[] signature = signer.generateSignature(LegacySignature.messageHash(message));
		final BigInteger n = LegacySignature.CURVE.getN();
		final BigInteger s = signature[1].compareTo(n.shiftRight(1)) > 0 ? n.subtract(signature[1]) : signature[1];

		return Bytes.concat(new byte[] { (byte) (27 + (compressed ? 4 : 0)) },
			BigIntegers.asUnsignedByteArray(COORDINATE, signature[0]), BigIntegers.asUnsignedByteArray(COORDINATE, s));
	}

	static byte[] withS(byte[] signature, BigInteger s) {
		final byte[] copy = Arrays.copyOf(signature, signature.length);

		System.arraycopy(BigIntegers.asUnsignedByteArray(COORDINATE, s), 0, copy, 1 + COORDINATE, COORDINATE);
		return copy;
	}

	static BigInteger sOf(byte[] signature) {
		return new BigInteger(1, Arrays.copyOfRange(signature, 1 + COORDINATE, signature.length));
	}
}
