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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.LongStream;
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
import org.apache.commons.codec.digest.DigestUtils;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.ReleaseKeyFixture;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class SnapshotBuilderTest {
	private static final byte[] PREVIOUS = { 1, 2, 3, 4, 5, 6, 7, 8 };
	private static final int BLOCK_SIZE = 256;
	private static final String DOWNLOADED = "/bootstrap.dat";

	private static boolean writtenTotalIsWrong;
	private static boolean firstBalanceIsOff;
	private static int addressesEncoded;
	private static Runnable whileTheCheckRuns = () -> { };

	/*
	   The fakes do the real work unless a test switches a failure on, and are installed once so they are not
	   stacked
	*/
	@BeforeContainer
	public static void beforeContainer() {
		new MockUp<SnapshotInspector>() {
			@Mock public /* static */ long totalBalance(Invocation invocation, Path snapshot) throws IOException {
				whileTheCheckRuns.run();
				return writtenTotalIsWrong ? -1 : invocation.<Long>proceed();
			}
		};

		new MockUp<LegacyAddress>() {
			@Mock public /* static */ String encode(Invocation invocation, byte[] addressHash) {
				addressesEncoded++;
				return invocation.proceed();
			}
		};

		new MockUp<Ledger>() {
			@Mock public long[] getBalances(Invocation invocation) {
				final long[] balances = invocation.proceed();

				if (firstBalanceIsOff) {
					final long[] off = balances.clone();

					off[0]++;
					return off;
				}

				return balances;
			}
		};
	}

	@BeforeTry
	public void beforeTry() {
		writtenTotalIsWrong = false;
		firstBalanceIsOff = false;
		addressesEncoded = 0;
		whileTheCheckRuns = () -> { };
	}

	/*
	   An address is only named when a balance disagrees, which is a defect in the build. Naming every
	   address of the legacy chain costs a double digest and a string each, for text nobody reads.
	*/
	@Example
	@SneakyThrows
	public void shouldNameNoAddressWhenEveryBalanceAgrees() {
		@Cleanup final FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
		final Path target = fs.getPath("/data/bootstrap.dat");

		Files.createDirectories(target.getParent());
		SnapshotBuilder.build(BlockFixture.directory(), target);

		assertThat(addressesEncoded, equalTo(0));
	}

	@Example
	@SneakyThrows
	public void shouldNameTheAddressWhoseBalanceDisagreesWithItsEntries() {
		@Cleanup final FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
		final Path target = fs.getPath("/data/bootstrap.dat");

		Files.createDirectories(target.getParent());
		firstBalanceIsOff = true;

		try {
			SnapshotBuilder.build(BlockFixture.directory(), target);
			throw new AssertionError("A ledger whose balance disagrees with its entries was reported as built");

		} catch (IllegalStateException expected) {
			assertThat(expected.getMessage(), matchesPattern(
				"Address [1-9A-HJ-NP-Za-km-z]{34} holds .* unspent but its entries sum to .*"));
			assertThat(Files.exists(target), equalTo(false));
		}
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

	/*
	   The node can run under another account than the one that builds the snapshot, and reads the file
	   through the permissions the operator gave it. The move into place carries the permissions of the
	   partial file onto the snapshot, so they have to be those of the snapshot it replaces by then.
	*/
	@Example
	@SneakyThrows
	public void shouldKeepTheModeOfTheSnapshotItReplaces() {
		@Cleanup final FileSystem fs = ownerOnlyUnlessAsked();
		final Path target = previousSnapshot(fs);

		Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-rw----"));
		SnapshotBuilder.build(BlockFixture.directory(), target);

		assertThat(modeOf(target), equalTo("rw-rw----"));
	}

	/*
	   A snapshot that replaces none is asked for as any new file is, with every permission to read and
	   to write, from which the mask of the process takes its share on the file system of the machine.
	   Nothing takes a share here.
	*/
	@Example
	@SneakyThrows
	public void shouldLeaveTheModeOfANewSnapshotToTheProcess() {
		@Cleanup final FileSystem fs = ownerOnlyUnlessAsked();
		final Path target = fs.getPath("/data/bootstrap.dat");

		Files.createDirectories(target.getParent());
		SnapshotBuilder.build(BlockFixture.directory(), target);

		assertThat(modeOf(target), equalTo("rw-rw-rw-"));
	}

	/*
	   A snapshot can be a link to a file that several nodes share. A move onto the link replaces the
	   link and leaves the file it names as it was, so the snapshot is built beside that file.
	*/
	@Example
	@SneakyThrows
	public void shouldBuildTheFileThatALinkNames() {
		@Cleanup final FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
		final Path link = fs.getPath("/data/bootstrap.dat");
		final Path shared = fs.getPath("/shared/snapshot.dat");
		final Path fresh = fs.getPath("/fresh.dat");

		Files.createDirectories(link.getParent());
		Files.createDirectories(shared.getParent());
		Files.write(shared, PREVIOUS);
		Files.createSymbolicLink(link, fs.getPath("../shared/snapshot.dat"));
		SnapshotBuilder.build(BlockFixture.directory(), fresh);
		SnapshotBuilder.build(BlockFixture.directory(), link);

		assertThat(Files.isSymbolicLink(link), equalTo(true));
		assertThat(Files.mismatch(fresh, shared), equalTo(-1L));
		assertThat(entriesOf(link.getParent()), equalTo(List.of("bootstrap.dat")));
		assertThat(entriesOf(shared.getParent()), equalTo(List.of("snapshot.dat")));
	}

	/*
	   A download clears the partial files of an earlier download out of the directory it installs into,
	   and an import of the blocks can run in that directory while the node fetches or starts. A download
	   that is refused after that sweep runs while the build checks its snapshot, which is when the
	   partial file of the build is on disk.
	*/
	@Example
	@SneakyThrows
	public void shouldKeepItsPartialFileWhenADownloadClearsTheDirectoryItBuildsIn() {
		@Cleanup final FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
		@Cleanup final LoopbackHost host = new LoopbackHost();
		final Path target = fs.getPath("/data/bootstrap.dat");
		final URL refused = refusedDownload(host);

		Files.createDirectories(target.getParent());

		whileTheCheckRuns = () -> {
			final List<String> building = entriesOf(target.getParent());

			assertThat(building, hasSize(1));
			refuse(refused, target.resolveSibling("downloaded.dat"));
			assertThat(entriesOf(target.getParent()), equalTo(building));
		};

		SnapshotBuilder.build(BlockFixture.directory(), target);

		assertThat(entriesOf(target.getParent()), equalTo(List.of("bootstrap.dat")));
	}

	/*
	   A kill or an exit skips the removal of the partial file and every build names a new one, so what
	   earlier builds left is still beside the target when the next one comes to write. A build is no
	   longer there when its process is gone, or when its file carries no process at all. The parent
	   of this process stands for a build that still runs elsewhere, and a file that merely begins and
	   ends like a partial file is not one.
	*/
	@Example
	@SneakyThrows
	public void shouldRemoveThePartialFilesThatKilledBuildsLeftBehind() {
		@Cleanup final FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
		final Path target = fs.getPath("/data/bootstrap.dat");
		final String running = "bootstrap-build-pid" + ProcessHandle.current().parent().orElseThrow().pid()
			+ "-3.tmp";

		Files.createDirectories(target.getParent());
		Files.write(target.resolveSibling("bootstrap-build-pid" + processThatIsGone() + "-1.tmp"), PREVIOUS);
		Files.write(target.resolveSibling("bootstrap-build-2.tmp"), PREVIOUS);
		Files.write(target.resolveSibling(running), PREVIOUS);
		Files.write(target.resolveSibling("bootstrap-build-notes.tmp"), PREVIOUS);
		SnapshotBuilder.build(BlockFixture.directory(), target);

		assertThat(entriesOf(target.getParent()), equalTo(List.of("bootstrap-build-notes.tmp", running,
			"bootstrap.dat")));
	}

	/*
	   Two imports of the blocks can write beside each other, to two targets in one directory. The one
	   that comes second starts while the first checks its snapshot, which is when the partial file of
	   the first is on disk, and the first still has to move that file into place.
	*/
	@Example
	@SneakyThrows
	public void shouldKeepThePartialFileOfABuildThatStillRuns() {
		@Cleanup final FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
		final Path target = fs.getPath("/data/bootstrap.dat");
		final Path second = fs.getPath("/data/second.dat");

		Files.createDirectories(target.getParent());

		whileTheCheckRuns = () -> {
			whileTheCheckRuns = () -> { };
			build(second);
		};

		SnapshotBuilder.build(BlockFixture.directory(), target);

		assertThat(Files.mismatch(target, second), equalTo(-1L));
		assertThat(entriesOf(target.getParent()), equalTo(List.of("bootstrap.dat", "second.dat")));
	}

	@SneakyThrows
	private static void build(Path target) {
		SnapshotBuilder.build(BlockFixture.directory(), target);
	}

	/* No process has an id this high, and the search goes down from it in case one ever does. */
	private static long processThatIsGone() {
		return LongStream.iterate(999_999_999L, id -> id - 1).filter(id -> ProcessHandle.of(id).isEmpty())
			.findFirst().orElseThrow();
	}

	@SneakyThrows
	private static URL refusedDownload(LoopbackHost host) {
		final byte[] hashFile = (DigestUtils.sha256Hex("another body") + "  " + DOWNLOADED.substring(1) + "\n")
			.getBytes(StandardCharsets.US_ASCII);

		host.serve(DOWNLOADED, "not a snapshot".getBytes(StandardCharsets.US_ASCII));
		host.serve(DOWNLOADED + SnapshotDownload.HASH_SUFFIX, hashFile);
		host.serve(DOWNLOADED + SnapshotDownload.HASH_SIGNATURE_SUFFIX, ReleaseKeyFixture.trusted().sign(hashFile));
		return host.url(DOWNLOADED);
	}

	private static void refuse(URL source, Path target) {
		try {
			SnapshotDownload.install(source, target);
			throw new AssertionError("A download that does not match its hash was installed");

		} catch (IOException expected) {
			assertThat(expected.getMessage(), containsString("does not match the hash"));
		}
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

	/*
	   The file system of the machine makes a temporary file for its owner alone when it is asked for
	   no permissions. This one does so for every file, the temporary ones among them.
	*/
	private static FileSystem ownerOnlyUnlessAsked() {
		return Jimfs.newFileSystem(Configuration.unix().toBuilder()
			.setAttributeViews("basic", "owner", "posix", "unix")
			.setDefaultAttributeValue("posix:permissions", PosixFilePermissions.fromString("rw-------"))
			.build());
	}

	@SneakyThrows
	private static String modeOf(Path file) {
		return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
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
