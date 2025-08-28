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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32C;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class BlockLogTest {
	private static Block block(int n) {
		return CodecFixtures.block(new Random(n), 2, 2);
	}

	private static Path logIn(FileSystem fileSystem) {
		return fileSystem.getPath("/data/ledger/blocks.log");
	}

	private static void append(Path file, Block... blocks) throws IOException {
		try (BlockLog log = BlockLog.open(file)) {
			for (final Block block : blocks) {
				log.append(block);
			}
		}
	}

	private static List<Block> read(Path file) throws IOException {
		try (BlockLog log = BlockLog.open(file)) {
			return log.blocks();
		}
	}

	private static void truncate(Path file, long size) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
			channel.truncate(size);
		}
	}

	private static void flipBit(Path file, long offset) throws IOException {
		final byte[] bytes = Files.readAllBytes(file);

		bytes[(int) offset] ^= 1;
		Files.write(file, bytes);
	}

	private static long recordSize(Block block) {
		return 2L * Integer.BYTES + BlockCodec.encode(block).length;
	}

	@Example
	public void shouldStartEmptyAndCreateItsDirectory() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			assertThat(read(logIn(fileSystem)), empty());
			assertThat(Files.exists(logIn(fileSystem)), equalTo(true));
		}
	}

	@Example
	public void shouldReadBackWhatWasAppended() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			append(logIn(fileSystem), block(1), block(2), block(3));
			assertThat(read(logIn(fileSystem)), equalTo(List.of(block(1), block(2), block(3))));
		}
	}

	@Example
	public void shouldKeepAppendingAcrossReopens() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			append(logIn(fileSystem), block(1));
			append(logIn(fileSystem), block(2));
			assertThat(read(logIn(fileSystem)), equalTo(List.of(block(1), block(2))));
		}
	}

	@Example
	public void shouldListBlocksAppendedAfterOpening() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());
			BlockLog log = BlockLog.open(logIn(fileSystem))) {
			log.append(block(1));
			assertThat(log.blocks(), equalTo(List.of(block(1))));
		}
	}

	/* A crash during an append can cut the last record anywhere; every cut must recover to the earlier blocks */
	@Example
	public void shouldDropATornLastRecordWhereverItIsCut() throws IOException {
		final long start = recordSize(block(1)) + recordSize(block(2));
		final long end = start + recordSize(block(3));

		for (long cut = start + 1; cut < end; cut++) {
			try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
				append(logIn(fileSystem), block(1), block(2), block(3));
				truncate(logIn(fileSystem), cut);

				assertThat("cut at " + cut, read(logIn(fileSystem)), equalTo(List.of(block(1), block(2))));
				assertThat(Files.size(logIn(fileSystem)), equalTo(start));

				append(logIn(fileSystem), block(4));
				assertThat(read(logIn(fileSystem)), equalTo(List.of(block(1), block(2), block(4))));
			}
		}
	}

	@Example
	public void shouldDropALastRecordWithABadChecksum() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			append(logIn(fileSystem), block(1), block(2));
			flipBit(logIn(fileSystem), Files.size(logIn(fileSystem)) - 1);
			assertThat(read(logIn(fileSystem)), equalTo(List.of(block(1))));
		}
	}

	/* A flipped bit with valid records after it is not a torn write; dropping the rest silently would lose blocks */
	@Example
	public void shouldRefuseABadChecksumBeforeTheLastRecord() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			append(logIn(fileSystem), block(1), block(2), block(3));

			final long insideSecond = recordSize(block(1)) + 2L * Integer.BYTES + 10;
			final long sizeBefore = Files.size(logIn(fileSystem));

			flipBit(logIn(fileSystem), insideSecond);
			assertThrows(CorruptLogException.class, () -> BlockLog.open(logIn(fileSystem)));
			assertThat(Files.size(logIn(fileSystem)), equalTo(sizeBefore));
		}
	}

	@Example
	public void shouldRefuseARecordThatChecksOutButDoesNotDecode() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final byte[] junk = new byte[10];
			final CRC32C crc = new CRC32C();
			final ByteBuffer record = ByteBuffer.allocate(2 * Integer.BYTES + junk.length);

			crc.update(junk);
			record.putInt(junk.length).putInt((int) crc.getValue()).put(junk);
			Files.createDirectories(logIn(fileSystem).getParent());
			Files.write(logIn(fileSystem), record.array());

			assertThrows(CorruptLogException.class, () -> BlockLog.open(logIn(fileSystem)));
		}
	}

	/* A write that fails half way must not leave its half record behind for the next append to write after */
	@Example
	public void shouldRollBackAPartlyWrittenRecordAndKeepGoing() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = logIn(fileSystem);

			Files.createDirectories(file.getParent());

			final FlakyChannel channel = new FlakyChannel(FileChannel.open(file, StandardOpenOption.READ,
				StandardOpenOption.WRITE, StandardOpenOption.CREATE));

			try (BlockLog log = BlockLog.over(channel)) {
				log.append(block(1));
				channel.failNextWriteAfter(20);
				assertThrows(IOException.class, () -> log.append(block(2)));
				assertThat(log.blocks(), equalTo(List.of(block(1))));
				log.append(block(3));
			}

			assertThat(read(file), equalTo(List.of(block(1), block(3))));
		}
	}

	@Example
	public void shouldTreatALengthPastTheEndAsATornWrite() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			append(logIn(fileSystem), block(1));
			Files.write(logIn(fileSystem), new byte[] { 0x7f, 0, 0, 0, 0, 0, 0, 0, 1, 2 }, StandardOpenOption.APPEND);
			assertThat(read(logIn(fileSystem)), equalTo(List.of(block(1))));
		}
	}
}
