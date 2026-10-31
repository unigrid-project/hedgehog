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

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

public class JournalTest {
	private record Record(long sequence, List<Slot> slots) {
	}

	/* A channel whose write and truncate fail, with the failures it throws kept to be compared */
	private static final class FailingChannel extends FileChannel {
		private final IOException write = new IOException("write");
		private final IOException truncate = new IOException("truncate");

		@Override
		public int write(final ByteBuffer source) throws IOException {
			throw write;
		}

		@Override
		public FileChannel truncate(final long size) throws IOException {
			throw truncate;
		}

		@Override
		public long position() {
			return 0;
		}

		@Override
		public FileChannel position(final long position) {
			return this;
		}

		@Override
		public int read(final ByteBuffer destination) {
			return -1;
		}

		@Override
		public long read(final ByteBuffer[] destinations, final int offset, final int length) {
			return -1;
		}

		@Override
		public long write(final ByteBuffer[] sources, final int offset, final int length) throws IOException {
			throw write;
		}

		@Override
		public long size() {
			return 0;
		}

		@Override
		public void force(final boolean metaData) {
		}

		@Override
		public long transferTo(final long position, final long count, final WritableByteChannel target) {
			return 0;
		}

		@Override
		public long transferFrom(final ReadableByteChannel source, final long position, final long count) {
			return 0;
		}

		@Override
		public int read(final ByteBuffer destination, final long position) {
			return -1;
		}

		@Override
		public int write(final ByteBuffer source, final long position) throws IOException {
			throw write;
		}

		@Override
		public MappedByteBuffer map(final MapMode mode, final long position, final long size) {
			throw new UnsupportedOperationException();
		}

		@Override
		public FileLock lock(final long position, final long size, final boolean shared) {
			throw new UnsupportedOperationException();
		}

		@Override
		public FileLock tryLock(final long position, final long size, final boolean shared) {
			throw new UnsupportedOperationException();
		}

		@Override
		protected void implCloseChannel() {
		}
	}

	private FileSystem files;
	private Path directory;

	@BeforeTry
	void openFiles() {
		files = PosixFiles.create();
		directory = files.getPath("/namespace");
	}

	@AfterTry
	void closeFiles() throws IOException {
		files.close();
	}

	@Provide
	Arbitrary<List<Batch>> batches() {
		return IndexArbitraries.batches().list().ofMinSize(1).ofMaxSize(8);
	}

	private List<Record> replay() throws IOException {
		final List<Record> records = new ArrayList<>();

		Journal.open(directory, (sequence, batch) -> records.add(new Record(sequence, List.copyOf(batch.slots()))))
			.close();

		return records;
	}

	private static List<Record> records(final List<Batch> batches) {
		final List<Record> records = new ArrayList<>();

		for (int i = 0; i < batches.size(); i++) {
			records.add(new Record(i + 1, List.copyOf(batches.get(i).slots())));
		}

		return records;
	}

	/* Appends every batch and answers where each record ends */
	private List<Long> append(final List<Batch> batches) throws IOException {
		final List<Long> ends = new ArrayList<>();

		Files.createDirectories(directory);

		try (Journal journal = Journal.open(directory, (sequence, batch) -> { })) {
			for (int i = 0; i < batches.size(); i++) {
				journal.append(i + 1, batches.get(i));
				ends.add(journal.size());
			}
		}

		return ends;
	}

	@Property(tries = 200)
	public void replaysWhatItAppended(@ForAll("batches") final List<Batch> batches) throws IOException {
		append(batches);
		assertThat(replay(), equalTo(records(batches)));
	}

	@Property(tries = 200)
	public void dropsATornTailAndAppendsAfterTheLastWholeRecord(@ForAll("batches") final List<Batch> batches,
		@ForAll @IntRange(max = 100) final int percent) throws IOException {

		final List<Long> ends = append(batches);
		final long cut = ends.getLast() * percent / 100;
		final int whole = (int) ends.stream().filter(end -> end <= cut).count();

		try (FileChannel channel = FileChannel.open(directory.resolve(Journal.ACTIVE), StandardOpenOption.WRITE)) {
			channel.truncate(cut);
		}

		assertThat(replay(), equalTo(records(batches.subList(0, whole))));

		try (Journal journal = Journal.open(directory, (sequence, batch) -> { })) {
			journal.append(whole + 1, batches.getFirst());
		}

		final List<Batch> expected = new ArrayList<>(batches.subList(0, whole));

		expected.add(batches.getFirst());
		assertThat(replay(), equalTo(records(expected)));
	}

	@Property(tries = 200)
	public void stopsAtARecordThatFailsItsChecksum(@ForAll("batches") final List<Batch> batches,
		@ForAll @IntRange(max = 100) final int percent) throws IOException {

		final List<Long> ends = append(batches);
		final long position = Math.min(ends.getLast() - 1, ends.getLast() * percent / 100);
		final int whole = (int) ends.stream().filter(end -> end <= position).count();
		final Path path = directory.resolve(Journal.ACTIVE);
		final byte[] content = Files.readAllBytes(path);

		content[(int) position] ^= 0x5A;
		Files.write(path, content);
		assertThat(replay(), equalTo(records(batches.subList(0, whole))));
	}

	@Property(tries = 100)
	public void keepsFrozenRecordsAheadOfNewerOnesUntilReleased(@ForAll("batches") final List<Batch> frozen,
		@ForAll("batches") final List<Batch> active) throws IOException {

		final List<Batch> all = new ArrayList<>(frozen);

		all.addAll(active);
		Files.createDirectories(directory);

		try (Journal journal = Journal.open(directory, (sequence, batch) -> { })) {
			for (int i = 0; i < all.size(); i++) {
				if (i == frozen.size()) {
					journal.freeze();
				}

				journal.append(i + 1, all.get(i));
			}
		}

		assertThat(replay(), equalTo(records(all)));

		try (Journal journal = Journal.open(directory, (sequence, batch) -> { })) {
			journal.freeze();
			journal.append(all.size() + 1, active.getFirst());
			journal.release();
		}

		assertThat(replay(), equalTo(List.of(new Record(all.size() + 1, List.copyOf(active.getFirst().slots())))));
	}

	@Example
	public void letsOnlyItsOwnerReadIt() throws IOException {
		Files.createDirectories(directory);
		Journal.open(directory, (sequence, batch) -> { }).close();

		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve(Journal.ACTIVE))),
			equalTo("rw-------"));
	}

	private static Batch oneWrite() {
		final Batch batch = new Batch();

		batch.put(new byte[] { 1 }, new byte[] { 2 });
		return batch;
	}

	/* The failure of the write is thrown, and the one of taking it back travels with it */
	@Example
	public void carriesAFailedTakeBackWithTheFailedWrite() throws Exception {
		Files.createDirectories(directory);

		final Journal journal = Journal.open(directory, (sequence, batch) -> { });
		final FailingChannel failing = new FailingChannel();
		final Field channel = Journal.class.getDeclaredField("channel");

		channel.setAccessible(true);
		journal.close();
		channel.set(journal, failing);

		try {
			journal.append(1, oneWrite());
			throw new AssertionError("The append did not fail");
		} catch (IOException failure) {
			assertThat(failure, sameInstance(failing.write));
			assertThat(failure.getSuppressed(), arrayContaining(failing.truncate));
		}

		try {
			journal.append(2, oneWrite());
			throw new AssertionError("The journal took a record after it broke");
		} catch (IOException refused) {
			assertThat(refused.getSuppressed().length, is(0));
		}
	}
}
