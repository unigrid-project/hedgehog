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
import java.nio.channels.FileChannel;
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
import static org.hamcrest.Matchers.equalTo;

public class JournalTest {
	private record Record(long sequence, List<Slot> slots) {
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
}
