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

package org.unigrid.hedgehog.ledger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;

public final class Digests {
	public static final int HASH_SIZE = 64;

	private Digests() {
		/* Static helpers only */
	}

	public static byte[] sha512(byte[]... parts) {
		return digest("SHA-512", parts);
	}

	/* The legacy chain hashes with a double SHA-256 wherever it signs */
	public static byte[] sha256Twice(byte[]... parts) {
		return digest("SHA-256", digest("SHA-256", parts));
	}

	/* How a legacy address is derived from a public key: RIPEMD-160 of its SHA-256 */
	public static byte[] hash160(byte[] data) {
		final byte[] sha256 = digest("SHA-256", data);
		final RIPEMD160Digest ripemd = new RIPEMD160Digest();
		final byte[] out = new byte[ripemd.getDigestSize()];

		ripemd.update(sha256, 0, sha256.length);
		ripemd.doFinal(out, 0);
		return out;
	}

	private static byte[] digest(String algorithm, byte[]... parts) {
		try {
			final MessageDigest digest = MessageDigest.getInstance(algorithm);

			for (final byte[] part : parts) {
				digest.update(part);
			}

			return digest.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(algorithm + " is required of every Java platform", e);
		}
	}
}
