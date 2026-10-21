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
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.BigIntegers;

/* A key of the legacy chain with the secret `secret`, signing the way its wallet does. The recovery id in the
   header is worked out from the nonce point of the signature itself and not by recovering the key, so a test of the
   recovery is not checked by the code it checks. */
final class LegacyWallet {
	private static final int COORDINATE = 32;
	private static final int FIRST_HEADER = 27;
	private static final int COMPRESSED_HEADER_OFFSET = 4;
	private static final int OVERFLOWED_ID = 2;
	private static final int ODD_ID = 1;

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

	Claim claim(AccountKey recipient, byte[] chainId) {
		return new Claim(publicKey, recipient, sign(Claim.message(chainId, recipient)));
	}

	/* Deterministic ECDSA (RFC 6979). A low s is the one of the two that is kept, which turns the nonce point over, so
	   the parity of its y flips with it. */
	byte[] sign(String message) {
		final BigInteger n = LegacySignature.CURVE.getN();
		final byte[] hash = LegacySignature.messageHash(message);
		final HMacDSAKCalculator calculator = new HMacDSAKCalculator(new SHA256Digest());

		calculator.init(n, secret, hash);

		final BigInteger k = calculator.nextK();
		final ECPoint nonce = LegacySignature.CURVE.getG().multiply(k).normalize();
		final BigInteger x = nonce.getAffineXCoord().toBigInteger();
		final BigInteger r = x.mod(n);
		final BigInteger s = k.modInverse(n).multiply(new BigInteger(1, hash).add(secret.multiply(r))).mod(n);
		final boolean flipped = s.compareTo(n.shiftRight(1)) > 0;
		final boolean odd = nonce.getAffineYCoord().toBigInteger().testBit(0) != flipped;
		final int recoveryId = (odd ? ODD_ID : 0) + (x.compareTo(n) >= 0 ? OVERFLOWED_ID : 0);
		final int header = FIRST_HEADER + recoveryId + (compressed ? COMPRESSED_HEADER_OFFSET : 0);

		return Bytes.concat(new byte[] { (byte) header }, BigIntegers.asUnsignedByteArray(COORDINATE, r),
			BigIntegers.asUnsignedByteArray(COORDINATE, flipped ? n.subtract(s) : s));
	}

	static byte[] withR(byte[] signature, BigInteger r) {
		return with(signature, r, 1);
	}

	static byte[] withS(byte[] signature, BigInteger s) {
		return with(signature, s, 1 + COORDINATE);
	}

	private static byte[] with(byte[] signature, BigInteger value, int offset) {
		final byte[] copy = Arrays.copyOf(signature, signature.length);

		System.arraycopy(BigIntegers.asUnsignedByteArray(COORDINATE, value), 0, copy, offset, COORDINATE);
		return copy;
	}

	static BigInteger sOf(byte[] signature) {
		return new BigInteger(1, Arrays.copyOfRange(signature, 1 + COORDINATE, signature.length));
	}
}
