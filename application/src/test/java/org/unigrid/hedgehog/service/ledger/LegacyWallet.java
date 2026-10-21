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

package org.unigrid.hedgehog.service.ledger;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.apache.commons.lang3.ArrayUtils;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.BigIntegers;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.LegacyAddress;
import org.unigrid.hedgehog.ledger.LegacySignature;

/* A key of the legacy chain with a secret the test knows, which signs the way the wallet of that chain does with
   signmessage. The recovery id in the header is worked out from the nonce point of the signature itself and not by
   recovering the key, so a test of the recovery is not checked by the code it checks. The message hash is written
   out here again because the ledger keeps its own to itself: a test that signs with the same code it checks with
   would prove nothing. */
public final class LegacyWallet {
	private static final ECDomainParameters CURVE = new ECDomainParameters(CustomNamedCurves.getByName("secp256k1"));
	private static final byte[] MAGIC = "DarkNet Signed Message:\n".getBytes(StandardCharsets.US_ASCII);
	private static final int COORDINATE = 32;
	private static final int FIRST_HEADER = 27;
	private static final int COMPRESSED_HEADER_OFFSET = 4;
	private static final int OVERFLOWED_ID = 2;
	private static final int ODD_ID = 1;

	private final BigInteger secret;
	private final boolean compressed;
	private final byte[] publicKey;

	public LegacyWallet(final int secret, final boolean compressed) {
		this.secret = BigInteger.valueOf(secret);
		this.compressed = compressed;
		this.publicKey = CURVE.getG().multiply(this.secret).getEncoded(compressed);
	}

	public byte[] publicKey() {
		return publicKey.clone();
	}

	public LegacyAddress address() {
		return LegacySignature.addressOf(publicKey);
	}

	public String hash160Hex() {
		return HexFormat.of().formatHex(address().bytes());
	}

	/* The address as the old chain and its snapshot write it */
	public String base58() {
		return org.unigrid.hedgehog.model.bootstrap.LegacyAddress.encode(address().bytes());
	}

	public Claim claim(final AccountKey recipient, final byte[] chainId) {
		return new Claim(publicKey, recipient, sign(Claim.message(chainId, recipient)));
	}

	/* Deterministic ECDSA (RFC 6979). A low s is the one of the two that is kept, which turns the nonce point over, so
	   the parity of its y flips with it. */
	public byte[] sign(final String message) {
		final BigInteger n = CURVE.getN();
		final byte[] hash = messageHash(message);
		final HMacDSAKCalculator calculator = new HMacDSAKCalculator(new SHA256Digest());

		calculator.init(n, secret, hash);

		final BigInteger k = calculator.nextK();
		final ECPoint nonce = CURVE.getG().multiply(k).normalize();
		final BigInteger x = nonce.getAffineXCoord().toBigInteger();
		final BigInteger r = x.mod(n);
		final BigInteger s = k.modInverse(n).multiply(new BigInteger(1, hash).add(secret.multiply(r))).mod(n);
		final boolean flipped = s.compareTo(n.shiftRight(1)) > 0;
		final boolean odd = nonce.getAffineYCoord().toBigInteger().testBit(0) != flipped;
		final int recoveryId = (odd ? ODD_ID : 0) + (x.compareTo(n) >= 0 ? OVERFLOWED_ID : 0);
		final byte header = (byte) (FIRST_HEADER + recoveryId + (compressed ? COMPRESSED_HEADER_OFFSET : 0));

		return ArrayUtils.addAll(new byte[] { header }, ArrayUtils.addAll(
			BigIntegers.asUnsignedByteArray(COORDINATE, r),
			BigIntegers.asUnsignedByteArray(COORDINATE, flipped ? n.subtract(s) : s)));
	}

	private static byte[] messageHash(final String message) {
		final byte[] text = message.getBytes(StandardCharsets.US_ASCII);
		final byte[] magicLength = { (byte) MAGIC.length };
		final byte[] textLength = { (byte) text.length };

		return Digests.sha256(Digests.sha256(magicLength, MAGIC, textLength, text));
	}
}
