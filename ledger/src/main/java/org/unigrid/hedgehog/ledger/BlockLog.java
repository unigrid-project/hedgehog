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

/* A record is the length of the block, a checksum of that length, a checksum of the block and the block.
   A block counts as stored only once append has forced it to disk, so a crash can only damage the last
   record, which open drops: it is cut short, or its file was extended with zeros. The checksum of the
   length is what tells a damaged length in the middle of the log, which is corruption and is refused, from
   a cut-off tail, which is not. Refusing is the safe answer: dropping a record also drops all behind it. */
@Slf4j
public final class BlockLog implements Closeable {
	static final int RECORD_HEADER = 3 * Integer.BYTES;
	static final int MAX_RECORD = BlockCodec.MAX_ENCODED_SIZE;
	private static final int ZERO_SCAN_CHUNK = 8192;

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

		return over(channel);
	}

	/* Takes over a channel that is open for reading and writing, and closes it if the log cannot be read */
	static BlockLog over(FileChannel channel) throws IOException {
		try {
			return new BlockLog(channel, recover(channel));
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	static byte[] recordHeader(int length, int dataChecksum) {
		return ByteBuffer.allocate(RECORD_HEADER).putInt(length).putInt(checksum(Bytes.intBytes(length)))
			.putInt(dataChecksum).array();
	}

	static byte[] encodeRecord(byte[] data) {
		return Bytes.concat(recordHeader(data.length, checksum(data)), data);
	}

	public synchronized List<Block> blocks() {
		return List.copyOf(blocks);
	}

	public synchronized void append(Block block) throws IOException {
		final ByteBuffer record = ByteBuffer.wrap(encodeRecord(BlockCodec.encode(block)));
		final long before = channel.position();

		try {
			while (record.hasRemaining()) {
				channel.write(record);
			}

			channel.force(false);
		} catch (IOException e) {
			rollBackTo(before, e);
			throw e;
		}

		blocks.add(block);
	}

	/* A write that failed part way leaves half a record, and the next append would write behind it and
	   corrupt the log from the middle. If even the cut fails, the file is for the next open to repair. */
	private void rollBackTo(long position, IOException cause) {
		try {
			channel.truncate(position);
			channel.position(position);
		} catch (IOException e) {
			cause.addSuppressed(e);
		}
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

	/* Empty when the record is a torn last write: a header cut short, a tail of zeros, data cut short, or a
	   checksum that fails on the very last record. Anything else that does not check out is corruption. */
	private static Optional<byte[]> readRecord(FileChannel channel, long start, long size) throws IOException {
		final ByteBuffer header = ByteBuffer.allocate(RECORD_HEADER);

		if (!readFully(channel, header, start)) {
			return Optional.empty();
		}

		final int length = header.flip().getInt();
		final int lengthChecksum = header.getInt();
		final int dataChecksum = header.getInt();

		if (lengthChecksum != checksum(Bytes.intBytes(length))) {
			return tornOrCorrupt(channel, start, size, "its length does not match its checksum");
		}

		if (length < 0 || length > MAX_RECORD) {
			throw new CorruptLogException("The block record at offset " + start + " has an impossible length");
		}

		return readData(channel, start, size, length, dataChecksum);
	}

	private static Optional<byte[]> readData(FileChannel channel, long start, long size, int length,
		int dataChecksum) throws IOException {
		final long end = start + RECORD_HEADER + (long) length;

		if (end > size) {
			return Optional.empty();
		}

		final byte[] data = new byte[length];

		readFully(channel, ByteBuffer.wrap(data), start + RECORD_HEADER);

		if (checksum(data) == dataChecksum) {
			return Optional.of(data);
		}

		if (end == size) {
			return Optional.empty();
		}

		throw new CorruptLogException("The block record at offset " + start + " fails its checksum");
	}

	private static Optional<byte[]> tornOrCorrupt(FileChannel channel, long start, long size, String why)
		throws IOException {
		if (isZeroFilled(channel, start, size)) {
			return Optional.empty();
		}

		throw new CorruptLogException("The block record at offset " + start + " is damaged: " + why);
	}

	private static boolean isZeroFilled(FileChannel channel, long from, long size) throws IOException {
		final ByteBuffer chunk = ByteBuffer.allocate(ZERO_SCAN_CHUNK);

		for (long at = from; at < size; at += ZERO_SCAN_CHUNK) {
			chunk.clear();
			readFully(channel, chunk, at);

			for (int i = 0; i < chunk.position(); i++) {
				if (chunk.get(i) != 0) {
					return false;
				}
			}
		}

		return true;
	}

	private static Block decode(byte[] data, long offset) throws CorruptLogException {
		try {
			return BlockCodec.decode(data);
		} catch (IllegalArgumentException e) {
			throw new CorruptLogException("The block record at offset " + offset + " does not decode: "
				+ e.getMessage());
		}
	}

	/* True when the buffer was filled; false when the file ended first, with what was there left in it */
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
