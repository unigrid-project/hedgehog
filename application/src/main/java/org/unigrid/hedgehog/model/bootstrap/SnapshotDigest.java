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

package org.unigrid.hedgehog.model.bootstrap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.AccessLevel;
import lombok.Cleanup;
import lombok.NoArgsConstructor;

/*
   A snapshot is far too large to hand to the signing code as one array, so it is signed by
   proxy: this streams a digest over the content and that digest is what gets signed. Anything
   appended after the content, which is where the signature itself lives, is not covered.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SnapshotDigest {
	public static final String ALGORITHM = "SHA-512";

	private static final int CHUNK_SIZE = 1 << 20;

	public static long contentLengthOf(Path snapshot) throws IOException {
		@Cleanup final FileChannel channel = FileChannel.open(snapshot, StandardOpenOption.READ);

		return SnapshotFormat.contentLength(readHeader(channel));
	}

	public static byte[] of(Path snapshot) throws IOException {
		@Cleanup final FileChannel channel = FileChannel.open(snapshot, StandardOpenOption.READ);
		final MessageDigest digest = createDigest();
		final ByteBuffer chunk = ByteBuffer.allocate(CHUNK_SIZE);
		long remaining = SnapshotFormat.contentLength(readHeader(channel));

		channel.position(0);

		while (remaining > 0) {
			chunk.clear().limit((int) Math.min(remaining, CHUNK_SIZE));

			final int read = channel.read(chunk);

			if (read <= 0) {
				throw new IOException("Snapshot ends before its content does");
			}

			digest.update(chunk.flip());
			remaining -= read;
		}

		return digest.digest();
	}

	private static ByteBuffer readHeader(FileChannel channel) throws IOException {
		final ByteBuffer header = ByteBuffer.allocate(SnapshotFormat.HEADER_SIZE)
			.order(ByteOrder.BIG_ENDIAN);

		channel.position(0);

		if (channel.read(header) != SnapshotFormat.HEADER_SIZE) {
			throw new IOException("Snapshot is too short to hold a header");
		}

		return header.flip();
	}

	private static MessageDigest createDigest() {
		try {
			return MessageDigest.getInstance(ALGORITHM);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(ALGORITHM + " is required but unavailable", e);
		}
	}
}
