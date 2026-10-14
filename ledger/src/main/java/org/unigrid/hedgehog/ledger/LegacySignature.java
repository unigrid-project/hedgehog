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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;

/* The signature that the wallet of the legacy chain makes with signmessage: 65 bytes, a header byte that says
   which encoding of the key it was made for (27 to 30 uncompressed, 31 to 34 compressed), then r and s. The
   owner of an address is whoever holds the key that hashes to it, so a signature that verifies under a key
   proves the funds of that key's address are theirs to move. */
public final class LegacySignature {
	public static final int SIZE = 65;
	static final String MAGIC = "DarkNet Signed Message:\n";
	static final ECDomainParameters CURVE = new ECDomainParameters(CustomNamedCurves.getByName("secp256k1"));

	private static final int FIRST_HEADER = 27;
	private static final int FIRST_COMPRESSED_HEADER = 31;
	private static final int LAST_HEADER = 34;
	static final int COMPRESSED_KEY_SIZE = 33;
	static final int UNCOMPRESSED_KEY_SIZE = 65;
	private static final int EVEN_Y_PREFIX = 2;
	private static final int ODD_Y_PREFIX = 3;
	private static final int UNCOMPRESSED_PREFIX = 4;
	private static final int COORDINATE_SIZE = 32;
	private static final int ONE_BYTE_LENGTH_LIMIT = 252;

	private LegacySignature() {
		/* Static helpers only */
	}

	public static LegacyAddress addressOf(byte[] publicKey) {
		return new LegacyAddress(Digests.hash160(publicKey));
	}

	/* A low s only, so the s and n - s twins are not both valid. The recovery id in the header is left unchecked
	   because the key is supplied, so one signature verifies under four header bytes: a claim is identified by
	   its address, never by its signature bytes. */
	public static boolean verify(byte[] publicKey, byte[] signature, String message) {
		if (signature.length != SIZE || !headerFits(publicKey, signature[0])) {
			return false;
		}

		final BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, 1, 1 + COORDINATE_SIZE));
		final BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, 1 + COORDINATE_SIZE, SIZE));

		if (s.compareTo(CURVE.getN().shiftRight(1)) > 0) {
			return false;
		}

		try {
			final ECDSASigner verifier = new ECDSASigner();

			verifier.init(false, new ECPublicKeyParameters(CURVE.getCurve().decodePoint(publicKey), CURVE));
			return verifier.verifySignature(messageHash(message), r, s);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static boolean headerFits(byte[] publicKey, byte header) {
		final int value = Byte.toUnsignedInt(header);

		if (value < FIRST_HEADER || value > LAST_HEADER) {
			return false;
		}

		return value >= FIRST_COMPRESSED_HEADER ? isCompressed(publicKey) : isUncompressed(publicKey);
	}

	/* Only the two encodings a wallet writes: a hybrid key decodes too, yet hashes to another address */
	private static boolean isCompressed(byte[] publicKey) {
		return publicKey.length == COMPRESSED_KEY_SIZE
			&& (publicKey[0] == EVEN_Y_PREFIX || publicKey[0] == ODD_Y_PREFIX);
	}

	private static boolean isUncompressed(byte[] publicKey) {
		return publicKey.length == UNCOMPRESSED_KEY_SIZE && publicKey[0] == UNCOMPRESSED_PREFIX;
	}

	/* The wallet hashes the magic and the message, each led by its length, twice with SHA-256. A length below 253
	   is a single byte, and the text of a claim is far shorter than that. Encoding a character outside ASCII would
	   turn it into a question mark and let two messages hash alike, so it is refused. */
	static byte[] messageHash(String message) {
		if (!StandardCharsets.US_ASCII.newEncoder().canEncode(message)) {
			throw new IllegalArgumentException("A message is plain ASCII");
		}

		final byte[] magic = MAGIC.getBytes(StandardCharsets.US_ASCII);
		final byte[] text = message.getBytes(StandardCharsets.US_ASCII);

		if (text.length > ONE_BYTE_LENGTH_LIMIT) {
			throw new IllegalArgumentException("A message is at most " + ONE_BYTE_LENGTH_LIMIT + " bytes, found "
				+ text.length);
		}

		return Digests.sha256(Digests.sha256(new byte[] { (byte) magic.length }, magic,
			new byte[] { (byte) text.length }, text));
	}
}
