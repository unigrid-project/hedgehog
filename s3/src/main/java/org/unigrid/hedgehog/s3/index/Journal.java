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

package org.unigrid.hedgehog.s3.index;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/* An append-only log of batches, each forced to disk before the write it holds counts as done. A record is its
   payload length and the CRC32C of the payload as two big-endian ints, then the payload: the sequence as a long,
   the slot count and every slot as its key bytes and its optional value bytes. Replay stops at the first record
   that is cut short or fails its checksum, which is the record a crash tore, and cuts the journal back to there.
   A flush freezes the journal under a second name while new writes go to a fresh one, and deletes the frozen
   one once the root names a manifest holding its records. */
final class Journal implements Closeable {
	static final String ACTIVE = "journal";
	static final String FROZEN = "journal.frozen";

	private static final int HEADER = 2 * Integer.BYTES;
	private static final Replay SKIP = (sequence, batch) -> { };

	@FunctionalInterface
	interface Replay {
		void apply(long sequence, Batch batch);
	}

	private final Path directory;
	private FileChannel channel;
	private boolean broken;

	private Journal(final Path directory, final FileChannel channel) {
		this.directory = directory;
		this.channel = channel;
	}

	static Journal open(final Path directory, final Replay replay) throws IOException {
		if (Files.exists(directory.resolve(FROZEN))) {
			absorbActive(directory);
		}

		final FileChannel channel = openActive(directory);

		try {
			channel.truncate(read(channel, replay));
			channel.position(channel.size());
		} catch (IOException | RuntimeException failure) {
			channel.close();
			throw failure;
		}

		return new Journal(directory, channel);
	}

	private static FileChannel openActive(final Path directory) throws IOException {
		final FileChannel channel = LocalFiles.open(directory.resolve(ACTIVE), StandardOpenOption.CREATE,
			StandardOpenOption.READ, StandardOpenOption.WRITE);

		LocalFiles.sync(directory);
		return channel.position(channel.size());
	}

	/* A crash during a flush leaves the frozen records behind with newer ones in the active journal. The frozen
	   journal takes in the records of the active one and replaces it, which keeps them in order. A crash halfway
	   repeats records on the next replay, which does no harm because every record writes absolute values. */
	private static void absorbActive(final Path directory) throws IOException {
		final Path active = directory.resolve(ACTIVE);
		final Path frozen = directory.resolve(FROZEN);

		try (FileChannel target = LocalFiles.open(frozen, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			target.truncate(read(target, SKIP));
			target.position(target.size());

			if (Files.exists(active)) {
				try (FileChannel source = LocalFiles.open(active, StandardOpenOption.READ)) {
					final long length = read(source, SKIP);

					for (long done = 0; done < length;) {
						done += source.transferTo(done, length - done, target);
					}
				}
			}

			target.force(true);
		}

		Files.move(frozen, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		LocalFiles.sync(directory);
	}

	/* Replays the leading whole records and answers how many bytes they take up */
	private static long read(final FileChannel channel, final Replay replay) throws IOException {
		final DataInputStream input = new DataInputStream(new BufferedInputStream(Channels.newInputStream(
			channel.position(0))));

		long valid = 0;
		byte[] payload;

		while ((payload = next(input, channel.size() - valid)) != null) {
			decode(payload, replay);
			valid += HEADER + payload.length;
		}

		return valid;
	}

	private static byte[] next(final DataInputStream input, final long remaining) throws IOException {
		if (remaining < HEADER) {
			return null;
		}

		final int length = input.readInt();
		final int checksum = input.readInt();

		if (length < 0 || length > remaining - HEADER) {
			return null;
		}

		final byte[] payload = input.readNBytes(length);
		return Codec.checksum(payload, payload.length) == checksum ? payload : null;
	}

	private static void decode(final byte[] payload, final Replay replay) throws IOException {
		Codec.decode(payload, input -> {
			final long sequence = input.readLong();
			final int count = Codec.readCount(input, 2);
			final Batch batch = new Batch();

			for (int i = 0; i < count; i++) {
				batch.write(Codec.readBytes(input), Codec.readNullable(input, Codec::readBytes));
			}

			replay.apply(sequence, batch);
			return batch;
		});
	}

	private static byte[] encode(final long sequence, final Batch batch) {
		return Codec.encode(output -> {
			output.writeLong(sequence);
			Codec.writeNumber(output, batch.slots().size());

			for (Slot slot : batch.slots()) {
				Codec.writeBytes(output, slot.key());
				Codec.writeNullable(output, slot.value(), Codec::writeBytes);
			}
		});
	}

	void append(final long sequence, final Batch batch) throws IOException {
		if (broken) {
			throw new IOException("The journal could not take back a failed write and accepts no more");
		}

		final byte[] payload = encode(sequence, batch);
		final long start = channel.position();

		try {
			LocalFiles.writeFully(channel, ByteBuffer.allocate(HEADER + payload.length).putInt(payload.length)
				.putInt(Codec.checksum(payload, payload.length)).put(payload).flip());

			channel.force(false);
		} catch (IOException failure) {
			takeBack(start, failure);
			throw failure;
		}
	}

	/* A record left half written would hide every record appended after it from the next replay. A failure to take
	   it back goes with the failure of the write. */
	private void takeBack(final long start, final IOException failure) {
		try {
			channel.truncate(start);
			channel.position(start);
		} catch (IOException exception) {
			failure.addSuppressed(exception);
			broken = true;
		}
	}

	void freeze() throws IOException {
		if (Files.exists(directory.resolve(FROZEN))) {
			throw new IllegalStateException("The journal is already frozen");
		}

		channel.close();

		try {
			Files.move(directory.resolve(ACTIVE), directory.resolve(FROZEN), StandardCopyOption.ATOMIC_MOVE);
		} finally {
			channel = openActive(directory);
		}
	}

	void release() throws IOException {
		Files.deleteIfExists(directory.resolve(FROZEN));
		LocalFiles.sync(directory);
	}

	long size() throws IOException {
		return channel.size();
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}
