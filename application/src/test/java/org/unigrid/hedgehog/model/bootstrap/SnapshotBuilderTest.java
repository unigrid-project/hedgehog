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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.Cleanup;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.jqwik.MockitHook;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class SnapshotBuilderTest {
	private static final byte[] PREVIOUS = { 1, 2, 3, 4, 5, 6, 7, 8 };
	private static final int BLOCK_SIZE = 256;

	private static boolean writtenTotalIsWrong;

	/* The fake does the real work unless a test switches the failure on, and is installed once so it is not stacked */
	@BeforeContainer
	public static void beforeContainer() {
		new MockUp<SnapshotInspector>() {
			@Mock public /* static */ long totalBalance(Invocation invocation, Path snapshot) throws IOException {
				return writtenTotalIsWrong ? -1 : invocation.<Long>proceed();
			}
		};
	}

	@BeforeTry
	public void beforeTry() {
		writtenTotalIsWrong = false;
	}

	/*
	   A snapshot that is rejected by its own check is complete on disk, so a reader would serve it as
	   unsigned. What was installed before must still be what is there.
	*/
	@Example
	@SneakyThrows
	public void shouldKeepThePreviousSnapshotWhenTheNewOneFailsItsCheck() {
		@Cleanup final FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
		final Path target = previousSnapshot(fs);

		writtenTotalIsWrong = true;

		try {
			SnapshotBuilder.build(BlockFixture.directory(), target);
			throw new AssertionError("A snapshot that failed its own check was reported as built");

		} catch (IllegalStateException expected) {
			assertThat(expected.getMessage(), containsString("address table sums to"));
			assertThat(Files.readAllBytes(target), equalTo(PREVIOUS));
			assertThat(entriesOf(target.getParent()), equalTo(List.of(target.getFileName().toString())));
		}
	}

	/* The disk gives out once about half of the snapshot is in place */
	@Example
	@SneakyThrows
	public void shouldKeepThePreviousSnapshotWhenTheDiskFillsUpDuringTheWrite() {
		@Cleanup final FileSystem fs = fullAfterHalfASnapshot();
		final Path target = previousSnapshot(fs);

		try {
			SnapshotBuilder.build(BlockFixture.directory(), target);
			throw new AssertionError("A snapshot that did not fit on the disk was reported as built");

		} catch (IOException expected) {
			assertThat(Files.readAllBytes(target), equalTo(PREVIOUS));
			assertThat(entriesOf(target.getParent()), equalTo(List.of(target.getFileName().toString())));
		}
	}

	@Example
	@SneakyThrows
	public void shouldLeaveNoFileBehindWhenThereWasNoSnapshotAndTheDiskFillsUp() {
		@Cleanup final FileSystem fs = fullAfterHalfASnapshot();
		final Path target = fs.getPath("/data/bootstrap.dat");

		Files.createDirectories(target.getParent());

		try {
			SnapshotBuilder.build(BlockFixture.directory(), target);
			throw new AssertionError("A snapshot that did not fit on the disk was reported as built");

		} catch (IOException expected) {
			assertThat(Files.exists(target), equalTo(false));
			assertThat(entriesOf(target.getParent()), empty());
		}
	}

	@Example
	@SneakyThrows
	public void shouldReplaceThePreviousSnapshotWhenTheBuildSucceeds() {
		@Cleanup final FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
		final Path target = previousSnapshot(fs);
		final Path fresh = fs.getPath("/data/fresh.dat");

		SnapshotBuilder.build(BlockFixture.directory(), fresh);
		SnapshotBuilder.build(BlockFixture.directory(), target);

		assertThat(Files.mismatch(fresh, target), equalTo(-1L));
		assertThat(entriesOf(target.getParent()), equalTo(List.of("bootstrap.dat", "fresh.dat")));
	}

	@SneakyThrows
	private static FileSystem fullAfterHalfASnapshot() {
		@Cleanup final FileSystem measuring = Jimfs.newFileSystem(configuration().build());
		final Path fresh = measuring.getPath("/fresh.dat");

		SnapshotBuilder.build(BlockFixture.directory(), fresh);
		return Jimfs.newFileSystem(configuration().setMaxSize(Files.size(fresh) / 2).build());
	}

	private static Configuration.Builder configuration() {
		return Configuration.unix().toBuilder().setBlockSize(BLOCK_SIZE);
	}

	@SneakyThrows
	private static Path previousSnapshot(FileSystem fs) {
		final Path target = fs.getPath("/data/bootstrap.dat");

		Files.createDirectories(target.getParent());
		Files.write(target, PREVIOUS);
		return target;
	}

	@SneakyThrows
	private static List<String> entriesOf(Path directory) {
		try (var entries = Files.list(directory)) {
			return entries.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}
}
