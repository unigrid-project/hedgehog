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

package org.unigrid.hedgehog.s3.cache;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/* A cached file open for reading, mapped in pieces since one mapping cannot span more than 2 GiB */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class CacheFile implements Closeable {
	private static final long MAPPING = 1L << 30;
	private static final int PIECE = 1 << 16;

	@Getter private final FileChannel channel;
	private final List<MappedByteBuffer> mappings;

	static CacheFile open(final Path path) throws IOException {
		final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);

		try {
			return new CacheFile(channel, map(channel));
		} catch (IOException ex) {
			channel.close();
			throw ex;
		}
	}

	/* A file system that cannot map, such as one held in memory, leaves no mappings and is read by position */
	private static List<MappedByteBuffer> map(final FileChannel channel) throws IOException {
		final List<MappedByteBuffer> mappings = new ArrayList<>();

		try {
			for (long position = 0; position < channel.size(); position += MAPPING) {
				mappings.add(channel.map(FileChannel.MapMode.READ_ONLY, position,
					Math.min(MAPPING, channel.size() - position)));
			}
		} catch (UnsupportedOperationException ex) {
			return List.of();
		}

		return mappings;
	}

	void writeTo(final OutputStream output, final long offset, final long length) throws IOException {
		final byte[] buffer = new byte[PIECE];
		final long end = offset + length;
		long position = offset;

		while (position < end) {
			final int piece = (int) Math.min(end - position, Math.min(PIECE, MAPPING - position % MAPPING));

			read(buffer, piece, position);
			output.write(buffer, 0, piece);
			position += piece;
		}
	}

	private void read(final byte[] buffer, final int length, final long position) throws IOException {
		if (mappings.isEmpty()) {
			final ByteBuffer target = ByteBuffer.wrap(buffer, 0, length);

			while (target.hasRemaining()) {
				if (channel.read(target, position + target.position()) < 0) {
					throw new EOFException("The cached file ends before its recorded size");
				}
			}
		} else {
			mappings.get((int) (position / MAPPING)).get((int) (position % MAPPING), buffer, 0, length);
		}
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}
