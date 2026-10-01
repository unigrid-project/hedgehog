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

package org.unigrid.hedgehog.model.storage.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class ChunkCipher {
	public static final int TAG_SIZE = 16;
	private static final int NONCE_SIZE = 12;
	private static final int CHUNK_DOMAIN = 0;
	private static final int MANIFEST_DOMAIN = 1;

	private final SecretKeySpec key;
	private final byte[] context;
	private final int domain;

	private ChunkCipher(byte[] key, String context, int domain) {
		this.key = new SecretKeySpec(key, "AES");
		this.context = context.getBytes(StandardCharsets.US_ASCII);
		this.domain = domain;
	}

	public static ChunkCipher forChunks(FingerprintKeys keys) {
		return new ChunkCipher(keys.chunkKey(), versioned("hh-chunk", keys), CHUNK_DOMAIN);
	}

	public static ChunkCipher forManifest(FingerprintKeys keys) {
		return new ChunkCipher(keys.manifestKey(), versioned("hh-manifest", keys), MANIFEST_DOMAIN);
	}

	private static String versioned(String name, FingerprintKeys keys) {
		return name + "-v" + (keys.format().getId() & 0xFF);
	}

	public byte[] seal(long sequence, byte[] plaintext) throws GeneralSecurityException {
		return cipher(Cipher.ENCRYPT_MODE, sequence).doFinal(plaintext);
	}

	public byte[] open(long sequence, byte[] ciphertext) throws GeneralSecurityException {
		return cipher(Cipher.DECRYPT_MODE, sequence).doFinal(ciphertext);
	}

	private Cipher cipher(int mode, long sequence) throws GeneralSecurityException {
		final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		final byte[] nonce = ByteBuffer.allocate(NONCE_SIZE).putLong(sequence).putInt(domain).array();

		cipher.init(mode, key, new GCMParameterSpec(TAG_SIZE * Byte.SIZE, nonce));
		cipher.updateAAD(ByteBuffer.allocate(context.length + Long.BYTES).put(context).putLong(sequence).array());
		return cipher;
	}
}
