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
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.unigrid.hedgehog.model.storage.StorageFormat;

public final class FingerprintKeys {
	public static final int KEY_SIZE = 32;
	public static final int MAX_POSITION = 0xFF;

	private final StorageFormat format;
	private final byte[] secret;

	public FingerprintKeys(Fingerprint fingerprint) {
		format = fingerprint.format();
		secret = fingerprint.secret();
	}

	public StorageFormat format() {
		return format;
	}

	public byte[] chunkKey() {
		return derive(label("enc", 0).array());
	}

	public byte[] manifestKey() {
		return derive(label("manifest-enc", 0).array());
	}

	public byte[] manifestSeed(int copy) {
		return derive(label("manifest", 1).put(unsignedByte(copy, "Manifest copy")).array());
	}

	public byte[] chunkSeed(int stripe, int index) {
		if (stripe < 0) {
			throw new IllegalArgumentException("Stripe must not be negative");
		}

		final byte position = unsignedByte(index, "Chunk index");
		return derive(label("chunk", Integer.BYTES + 1).putInt(stripe).put(position).array());
	}

	/* Seeds carry positions as a single byte, so a wider value would wrap and share a seed with another position */
	private static byte unsignedByte(int value, String name) {
		if (value < 0 || value > MAX_POSITION) {
			throw new IllegalArgumentException(name + " must be within 0.." + MAX_POSITION);
		}

		return (byte) value;
	}

	private static ByteBuffer label(String name, int suffixBytes) {
		final byte[] text = name.getBytes(StandardCharsets.US_ASCII);
		return ByteBuffer.allocate(text.length + suffixBytes).put(text);
	}

	private byte[] derive(byte[] info) {
		final HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
		final byte[] key = new byte[KEY_SIZE];

		generator.init(new HKDFParameters(secret, format.salt(), info));
		generator.generateBytes(key, 0, KEY_SIZE);
		return key;
	}
}
