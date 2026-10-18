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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

public class SnapshotDigestTest {
	@Example
	@SneakyThrows
	public void shouldCoverTheWholeFileWhenNothingIsAppended() {
		final Path snapshot = snapshot();

		assertThat(SnapshotDigest.contentLengthOf(snapshot), equalTo(Files.size(snapshot)));
	}

	@Example
	@SneakyThrows
	public void shouldProduceASha512() {
		assertThat(SnapshotDigest.of(snapshot()).length, equalTo(64));
	}

	@Example
	@SneakyThrows
	public void shouldIgnoreAnythingAppendedAfterTheContent() {
		final Path snapshot = snapshot();
		final byte[] before = SnapshotDigest.of(snapshot);

		Files.write(snapshot, new byte[] { 1, 2, 3 }, StandardOpenOption.APPEND);

		assertThat(SnapshotDigest.contentLengthOf(snapshot), not(equalTo(Files.size(snapshot))));
		assertThat(SnapshotDigest.of(snapshot), equalTo(before));
	}

	@Example
	@SneakyThrows
	public void shouldChangeWhenTheContentChanges() {
		final Path snapshot = snapshot();
		final byte[] before = SnapshotDigest.of(snapshot);
		final byte[] contents = Files.readAllBytes(snapshot);

		contents[(int) SnapshotDigest.contentLengthOf(snapshot) - 1] ^= 0x01;
		Files.write(snapshot, contents);

		assertThat(SnapshotDigest.of(snapshot), not(equalTo(before)));
	}

	@SneakyThrows
	private static Path snapshot() {
		final Path path = Files.createTempFile("hhg-digest-", ".dat");

		path.toFile().deleteOnExit();
		SnapshotBuilder.build(BlockFixture.directory(), path);
		return path;
	}

	@SneakyThrows
	@Property
	public void shouldRefuseAFileShorterThanAHeader(@ForAll @Size(max = SnapshotFormat.HEADER_SIZE - 1) byte[] contents) {
		final Path snapshot = SyntheticSnapshots.inMemory(contents);

		SyntheticSnapshots.assertRefused(() -> SnapshotDigest.contentLengthOf(snapshot), "too short to hold a header");
		SyntheticSnapshots.assertRefused(() -> SnapshotDigest.of(snapshot), "too short to hold a header");
	}

	@SneakyThrows
	@Property
	public void shouldRefuseAFileThatEndsBeforeItsContent(@ForAll @IntRange(min = 1, max = 64) int transactions,
		@ForAll @IntRange(min = 1, max = Hashing.HASH_SIZE) int missing) {

		final byte[] header = SyntheticSnapshots.builder().transactionTableOffset(SnapshotFormat.HEADER_SIZE)
			.transactionCount(transactions).build();
		final Path snapshot = SyntheticSnapshots.inMemory(Arrays.copyOf(header,
			header.length + transactions * Hashing.HASH_SIZE - missing)
		);

		SyntheticSnapshots.assertRefused(() -> SnapshotDigest.of(snapshot), "ends before its content does");
	}

	@SneakyThrows
	@Property
	public void shouldDigestOnlyTheContent(@ForAll @Size(max = 64) List<@Size(Hashing.HASH_SIZE) byte[]> transactionIds,
		@ForAll @Size(max = 512) byte[] appended) {

		final byte[] header = SyntheticSnapshots.builder().transactionTableOffset(SnapshotFormat.HEADER_SIZE)
			.transactionCount(transactionIds.size()).build();
		final ByteArrayOutputStream content = new ByteArrayOutputStream();

		content.write(header);
		transactionIds.forEach(content::writeBytes);

		final Path snapshot = SyntheticSnapshots.inMemory(ByteBuffer.allocate(content.size() + appended.length)
			.put(content.toByteArray()).put(appended).array()
		);

		assertThat(SnapshotDigest.contentLengthOf(snapshot), equalTo((long) content.size()));
		assertThat(SnapshotDigest.of(snapshot),
			equalTo(MessageDigest.getInstance(SnapshotDigest.ALGORITHM).digest(content.toByteArray()))
		);
	}
}
