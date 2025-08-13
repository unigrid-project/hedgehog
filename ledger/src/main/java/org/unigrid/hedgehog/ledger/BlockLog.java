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

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32C;
import lombok.extern.slf4j.Slf4j;

/* Records are length, CRC32C and the encoded block. A block counts as stored only once append has forced
   it to disk, so the only damage a crash can do is a torn last record, which open drops. A bad record with
   more data after it is real corruption and is refused instead of cutting everything behind it off. */
@Slf4j
public final class BlockLog implements Closeable {
	private static final int RECORD_HEADER = 2 * Integer.BYTES;
	private static final int MAX_RECORD = 16 * 1024 * 1024;

	private final FileChannel channel;
	private final List<Block> blocks;

	private BlockLog(FileChannel channel, List<Block> blocks) {
		this.channel = channel;
		this.blocks = blocks;
	}

	public static BlockLog open(Path file) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());

		final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE,
			StandardOpenOption.CREATE);

		try {
			return new BlockLog(channel, recover(channel));
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	public synchronized List<Block> blocks() {
		return List.copyOf(blocks);
	}

	public synchronized void append(Block block) throws IOException {
		final byte[] data = BlockCodec.encode(block);
		final ByteBuffer record = ByteBuffer.allocate(RECORD_HEADER + data.length).putInt(data.length)
			.putInt(checksum(data)).put(data).flip();

		while (record.hasRemaining()) {
			channel.write(record);
		}

		channel.force(false);
		blocks.add(block);
	}

	@Override
	public synchronized void close() throws IOException {
		channel.close();
	}

	private static List<Block> recover(FileChannel channel) throws IOException {
		final long size = channel.size();
		final List<Block> blocks = new ArrayList<>();
		long start = 0;

		while (start < size) {
			final Optional<byte[]> data = readRecord(channel, start, size);

			if (data.isEmpty()) {
				break;
			}

			blocks.add(decode(data.get(), start));
			start += RECORD_HEADER + data.get().length;
		}

		if (start < size) {
			log.atWarn().log("Dropping {} bytes of a torn last block record at offset {}", size - start, start);
			channel.truncate(start);
		}

		channel.position(start);
		return blocks;
	}

	/* Empty when the record is a torn last write: cut short, a length that runs past the end of the file, or
	   a checksum that fails on the very last record. A failing checksum with more data after it is corruption. */
	private static Optional<byte[]> readRecord(FileChannel channel, long start, long size) throws IOException {
		final ByteBuffer header = ByteBuffer.allocate(RECORD_HEADER);

		if (!readFully(channel, header, start)) {
			return Optional.empty();
		}

		final int length = header.flip().getInt();
		final int expected = header.getInt();
		final long end = start + RECORD_HEADER + (long) length;

		if (!isPlausible(length, end, size)) {
			return Optional.empty();
		}

		final byte[] data = new byte[length];

		readFully(channel, ByteBuffer.wrap(data), start + RECORD_HEADER);

		if (checksum(data) == expected) {
			return Optional.of(data);
		}

		if (end == size) {
			return Optional.empty();
		}

		throw new CorruptLogException("The block record at offset " + start + " fails its checksum");
	}

	private static boolean isPlausible(int length, long end, long size) {
		return length >= 0 && length <= MAX_RECORD && end <= size;
	}

	private static Block decode(byte[] data, long offset) throws CorruptLogException {
		try {
			return BlockCodec.decode(data);
		} catch (IllegalArgumentException e) {
			throw new CorruptLogException("The block record at offset " + offset + " does not decode: "
				+ e.getMessage());
		}
	}

	private static boolean readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
		long at = position;

		while (buffer.hasRemaining()) {
			final int read = channel.read(buffer, at);

			if (read < 0) {
				return false;
			}

			at += read;
		}

		return true;
	}

	private static int checksum(byte[] data) {
		final CRC32C crc = new CRC32C();

		crc.update(data);
		return (int) crc.getValue();
	}
}
