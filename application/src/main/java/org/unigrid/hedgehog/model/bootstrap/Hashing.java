/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.bootstrap;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Hashing {
	public static final int HASH_SIZE = 32;
	public static final int ADDRESS_HASH_SIZE = 20;

	private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(Hashing::createSha256);

	public static byte[] doubleSha256(ByteBuffer input) {
		final MessageDigest digest = SHA256.get();

		digest.reset();
		digest.update(input);
		return digest.digest(digest.digest());
	}

	public static byte[] addressHash(byte[] publicKey) {
		final byte[] sha = SHA256.get().digest(publicKey);
		final RIPEMD160Digest ripemd = new RIPEMD160Digest();
		final byte[] result = new byte[ADDRESS_HASH_SIZE];

		ripemd.update(sha, 0, sha.length);
		ripemd.doFinal(result, 0);
		return result;
	}

	public static byte[] checksum(byte[] input) {
		final MessageDigest digest = SHA256.get();

		digest.reset();
		return digest.digest(digest.digest(input));
	}

	private static MessageDigest createSha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required but unavailable", e);
		}
	}
}
