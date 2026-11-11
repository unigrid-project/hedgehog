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

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import lombok.Cleanup;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import org.apache.commons.codec.digest.DigestUtils;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.ReleaseKeyFixture;
import org.unigrid.hedgehog.model.crypto.Signature;

public class SnapshotDownloadTest {
	private static final String PLAIN = "/bootstrap.dat";
	private static final String COMPRESSED = "/bootstrap.dat.gz";
	private static final Duration WAIT = Duration.ofSeconds(3);

	@Example
	@SneakyThrows
	public void shouldInstallASignedSnapshot() {
		final Path source = signedSnapshot();
		final Path target = target();

		SnapshotDownload.install(published(source), target);

		assertThat(Files.mismatch(source, target), equalTo(-1L));
	}

	@Example
	@SneakyThrows
	public void shouldReportTheProgressOfTheTransferUpToComplete() {
		final Path compressed = gzip(signedSnapshot());
		final List<Integer> reported = new ArrayList<>();

		SnapshotDownload.install(published(compressed), target(), reported::add);

		assertThat(reported.getLast(), equalTo(100));
		assertThat(reported, equalTo(reported.stream().sorted().distinct().toList()));
	}

	@Example
	@SneakyThrows
	public void shouldAbandonADownloadThatPassesItsCeiling() {
		final Path source = signedSnapshot();
		final Path target = target();

		try {
			SnapshotDownload.install(published(source), target, 1024, percent -> { });
			throw new AssertionError("An oversized download was installed");

		} catch (IOException expected) {
			assertThat(expected.getMessage(), containsString("was abandoned"));
			assertThat(Files.exists(target), equalTo(false));
			assertThat(leftoverCount(target.getParent()), equalTo(0L));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseASnapshotThisBuildCannotRead() {
		final Path source = signedSnapshot();
		final Path target = target();
		final byte[] contents = Files.readAllBytes(source);

		contents[SnapshotFormat.VERSION_OFFSET + 3] = (byte) (SnapshotFormat.VERSION + 1);
		Files.write(source, contents);

		try {
			SnapshotDownload.install(published(source), target);
			throw new AssertionError("A snapshot of an unreadable format version was installed");

		} catch (IOException expected) {
			assertThat(expected.getMessage(), containsString("format version"));
			assertThat(Files.exists(target), equalTo(false));
		}
	}

	/*
	   The old code wrote to a fixed "<target>.part". Occupying that exact name with a directory makes
	   this test fail against it and pass only against a name the caller cannot predict.
	*/
	@Example
	@SneakyThrows
	public void shouldNotWriteToAPredictablePartialName() {
		final Path source = signedSnapshot();
		final Path target = target();
		final Path predictable = target.resolveSibling(target.getFileName() + ".part");

		Files.createDirectory(predictable);
		SnapshotDownload.install(published(source), target);

		assertThat(Files.mismatch(source, target), equalTo(-1L));
		assertThat(Files.isDirectory(predictable), equalTo(true));
		assertThat(leftoverCount(target.getParent()), equalTo(0L));
	}

	@Example
	@SneakyThrows
	public void shouldRefuseASnapshotPublishedWithoutAHash() {
		assertRefused(signedSnapshot().toUri().toURL(), "Could not fetch");
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAHashNotSignedByTheReleaseKey() {
		final Path source = signedSnapshot();

		ReleaseKeyFixture.trusted();
		ReleaseKeyFixture.foreign().publish(source);
		assertRefused(source.toUri().toURL(), "no valid signature");
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAHashOfAnotherFile() {
		final Path source = signedSnapshot();

		ReleaseKeyFixture.trusted().publishHash(source, DigestUtils.sha256Hex(Files.readAllBytes(source))
			+ "  bootstrap-of-another-release.dat\n");
		assertRefused(source.toUri().toURL(), "is not a hash of");
	}

	@Example
	@SneakyThrows
	public void shouldRefuseADownloadThatDoesNotMatchItsHash() {
		final Path source = signedSnapshot();
		final URL url = published(source);

		Files.write(source, new byte[] { 0 }, StandardOpenOption.APPEND);
		assertRefused(url, "does not match the hash");
	}

	@SneakyThrows
	private static void assertRefused(URL source, String reason) {
		final Path target = Files.copy(signedSnapshot(), target());
		final byte[] before = Files.readAllBytes(target);

		try {
			SnapshotDownload.install(source, target);
			throw new AssertionError("A snapshot without a matching signed hash was installed");

		} catch (IOException expected) {
			assertThat(expected.getMessage(), containsString(reason));
			assertThat(Files.readAllBytes(target), equalTo(before));
			assertThat(leftoverCount(target.getParent()), equalTo(0L));
		}
	}

	@SneakyThrows
	private static URL published(Path asset) {
		ReleaseKeyFixture.trusted().publish(asset);
		return asset.toUri().toURL();
	}

	@SneakyThrows
	private static long leftoverCount(Path directory) {
		try (var entries = Files.list(directory)) {
			return entries.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().endsWith(".part")).count();
		}
	}

	@Example
	@SneakyThrows
	public void shouldInstallASignedSnapshotThatArrivesCompressed() {
		final Path source = signedSnapshot();
		final Path compressed = gzip(source);
		final Path target = target();

		SnapshotDownload.install(published(compressed), target);

		assertThat(Files.mismatch(source, target), equalTo(-1L));
	}

	@Example
	@SneakyThrows
	public void shouldLeaveAnExistingSnapshotAloneWhenTheDownloadIsNotSigned() {
		final Path target = signedSnapshot();
		final byte[] before = Files.readAllBytes(target);
		final Path unsigned = snapshot();

		try {
			SnapshotDownload.install(published(unsigned), target);
			throw new AssertionError("An unverified snapshot was installed");

		} catch (IOException expected) {
			assertThat(Files.readAllBytes(target), equalTo(before));
		}
	}

	@Example
	@SneakyThrows
	public void shouldNotLeaveATemporaryFileBehindWhenItRefuses() {
		final Path target = target();
		final Path unsigned = snapshot();

		try {
			SnapshotDownload.install(published(unsigned), target);
			throw new AssertionError("An unverified snapshot was installed");

		} catch (IOException expected) {
			try (var entries = Files.list(target.getParent())) {
				assertThat(entries.anyMatch(p -> p.getFileName().toString().endsWith(".part")),
					equalTo(false));
			}
		}
	}

	/*
	   A kill or an exit skips the clean-up of a download, so the partial file of an earlier start is
	   still in the data directory when the next start comes to download.
	*/
	@Example
	@SneakyThrows
	public void shouldSweepThePartialDownloadsOfAnEarlierStartWhenTheDownloadFails() {
		final Path target = dataDirectoryWithAnOldPartial();

		try (LoopbackHost host = new LoopbackHost()) {
			host.serve(PLAIN, "not a snapshot".getBytes(StandardCharsets.US_ASCII));
			publish(host, PLAIN, hashLine(PLAIN, "another body".getBytes(StandardCharsets.US_ASCII)));

			try {
				SnapshotDownload.install(host.url(PLAIN), target);
				throw new AssertionError("A download that does not match its hash was installed");

			} catch (IOException expected) {
				assertThat(expected.getMessage(), containsString("does not match the hash"));
			}
		}

		assertThat(leftoverCount(target.getParent()), equalTo(0L));
		assertThat(Files.exists(target.resolveSibling("notes.txt")), equalTo(true));
	}

	@Example
	@SneakyThrows
	public void shouldSweepThePartialDownloadsOfAnEarlierStartWhenTheDownloadSucceeds() {
		final byte[] snapshot = Files.readAllBytes(signedSnapshot());
		final Path target = dataDirectoryWithAnOldPartial();

		try (LoopbackHost host = new LoopbackHost()) {
			host.serve(PLAIN, snapshot);
			publish(host, PLAIN, hashLine(PLAIN, snapshot));
			SnapshotDownload.install(host.url(PLAIN), target);
		}

		assertThat(Files.readAllBytes(target), equalTo(snapshot));
		assertThat(leftoverCount(target.getParent()), equalTo(0L));
		assertThat(Files.exists(target.resolveSibling("notes.txt")), equalTo(true));
	}

	/*
	   The decompressor reads its header when it is constructed and gives up on a body that is not gzip
	   without closing the transfer it was handed. The host keeps writing without end, so a write on
	   its side fails only when the node has closed the transfer.
	*/
	@Example
	@SneakyThrows
	public void shouldCloseTheTransferOfABodyThatIsNotGzip() {
		try (LoopbackHost host = new LoopbackHost()) {
			host.serve(COMPRESSED, LoopbackHost.filled((byte) 'x', Long.MAX_VALUE));
			publish(host, COMPRESSED, hashLine(COMPRESSED, new byte[0]));

			try {
				SnapshotDownload.install(host.url(COMPRESSED), inMemoryTarget());
				throw new AssertionError("A body that is not gzip was installed");

			} catch (IOException expected) {
				assertThat(expected.getMessage(), containsString("Not in GZIP format"));
			}

			await("The host finds the transfer closed").atMost(WAIT).until(host::abandoned, greaterThan(0));
		}
	}

	/*
	   The compressed stream ends where its last member ends, and whatever follows it is still read so
	   the hash covers the transfer to its last byte. That read has to stop at the ceiling as well, or a
	   host that never stops writing keeps the node reading.
	*/
	@Example
	@SneakyThrows
	public void shouldAbandonADownloadThatTrailsPastItsCeiling() {
		final Path target = dataDirectoryWithAnOldPartial();

		try (LoopbackHost host = new LoopbackHost()) {
			host.serve(COMPRESSED, out -> {
				out.write(gzipped(new byte[100]));
				LoopbackHost.filled((byte) 'x', 32L << 20).writeTo(out);
			});
			publish(host, COMPRESSED, hashLine(COMPRESSED, new byte[0]));

			try {
				SnapshotDownload.install(host.url(COMPRESSED), target, 4096, percent -> { });
				throw new AssertionError("A download that trails past its ceiling was installed");

			} catch (IOException expected) {
				assertThat(expected.getMessage(), containsString("was abandoned"));
			}
		}

		assertThat(Files.exists(target), equalTo(false));
	}

	/*
	   The fetch command takes the target from the operator, and a bare file name has no parent directory
	   of its own: the snapshot goes to the working directory.
	*/
	@Example
	@SneakyThrows
	public void shouldInstallToABareFileNameInTheWorkingDirectory() {
		final byte[] snapshot = Files.readAllBytes(signedSnapshot());
		final FileSystem files = Jimfs.newFileSystem(Configuration.unix().toBuilder()
			.setWorkingDirectory("/work").build());

		Files.createDirectories(files.getPath("/work"));

		try (LoopbackHost host = new LoopbackHost()) {
			host.serve(PLAIN, snapshot);
			publish(host, PLAIN, hashLine(PLAIN, snapshot));
			SnapshotDownload.install(host.url(PLAIN), files.getPath("bootstrap.dat"));
		}

		assertThat(Files.readAllBytes(files.getPath("/work/bootstrap.dat")), equalTo(snapshot));
		assertThat(leftoverCount(files.getPath("/work")), equalTo(0L));
	}

	@SneakyThrows
	private static Path inMemoryTarget() {
		final Path directory = Jimfs.newFileSystem(Configuration.unix()).getPath("/data");

		Files.createDirectories(directory);
		return directory.resolve("bootstrap.dat");
	}

	@SneakyThrows
	private static Path dataDirectoryWithAnOldPartial() {
		final Path target = inMemoryTarget();

		Files.write(target.resolveSibling("snapshot-1.part"), new byte[] { 1, 2, 3 });
		Files.write(target.resolveSibling("notes.txt"), new byte[] { 4 });
		return target;
	}

	private static void publish(LoopbackHost host, String path, String hashFile) {
		final byte[] contents = hashFile.getBytes(StandardCharsets.US_ASCII);

		host.serve(path + SnapshotDownload.HASH_SUFFIX, contents);
		host.serve(path + SnapshotDownload.HASH_SIGNATURE_SUFFIX, ReleaseKeyFixture.trusted().sign(contents));
	}

	private static String hashLine(String path, byte[] contents) {
		return DigestUtils.sha256Hex(contents) + "  " + path.substring(1) + "\n";
	}

	@SneakyThrows
	private static byte[] gzipped(byte[] contents) {
		final ByteArrayOutputStream compressed = new ByteArrayOutputStream();

		try (OutputStream stream = new GZIPOutputStream(compressed)) {
			stream.write(contents);
		}

		return compressed.toByteArray();
	}

	@SneakyThrows
	private static Path gzip(Path source) {
		final Path compressed = Files.createTempFile("hhg-asset-", ".dat.gz");

		compressed.toFile().deleteOnExit();

		@Cleanup final OutputStream stream = new GZIPOutputStream(Files.newOutputStream(compressed));

		Files.copy(source, stream);
		return compressed;
	}

	@SneakyThrows
	private static Path signedSnapshot() {
		final Path path = snapshot();
		final Signature key = new Signature();

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { key.getPublicKey() };
			}
		};

		SnapshotSignature.signAndAppend(path, key.getPrivateKey());
		return path;
	}

	@SneakyThrows
	private static Path snapshot() {
		final Path path = Files.createTempFile("hhg-source-", ".dat");

		path.toFile().deleteOnExit();
		SnapshotBuilder.build(BlockFixture.directory(), path);
		return path;
	}

	@SneakyThrows
	private static Path target() {
		final Path directory = Files.createTempDirectory("hhg-install-");

		directory.toFile().deleteOnExit();
		return directory.resolve("bootstrap.dat");
	}
}
