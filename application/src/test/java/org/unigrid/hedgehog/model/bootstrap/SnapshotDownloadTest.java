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
import static org.hamcrest.Matchers.equalTo;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import lombok.Cleanup;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;

public class SnapshotDownloadTest {
	@Example
	@SneakyThrows
	public void shouldInstallASignedSnapshot() {
		final Path source = signedSnapshot();
		final Path target = target();

		SnapshotDownload.install(source.toUri().toURL(), target);

		assertThat(Files.mismatch(source, target), equalTo(-1L));
	}

	@Example
	@SneakyThrows
	public void shouldAbandonADownloadThatPassesItsCeiling() {
		final Path source = signedSnapshot();
		final Path target = target();

		try {
			SnapshotDownload.install(source.toUri().toURL(), target, 1024);
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
			SnapshotDownload.install(source.toUri().toURL(), target);
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
		SnapshotDownload.install(source.toUri().toURL(), target);

		assertThat(Files.mismatch(source, target), equalTo(-1L));
		assertThat(Files.isDirectory(predictable), equalTo(true));
		assertThat(leftoverCount(target.getParent()), equalTo(0L));
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

		SnapshotDownload.install(compressed.toUri().toURL(), target);

		assertThat(Files.mismatch(source, target), equalTo(-1L));
	}

	@Example
	@SneakyThrows
	public void shouldLeaveAnExistingSnapshotAloneWhenTheDownloadIsNotSigned() {
		final Path target = signedSnapshot();
		final byte[] before = Files.readAllBytes(target);
		final Path unsigned = snapshot();

		try {
			SnapshotDownload.install(unsigned.toUri().toURL(), target);
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
			SnapshotDownload.install(unsigned.toUri().toURL(), target);
			throw new AssertionError("An unverified snapshot was installed");

		} catch (IOException expected) {
			try (var entries = Files.list(target.getParent())) {
				assertThat(entries.anyMatch(p -> p.getFileName().toString().endsWith(".part")),
					equalTo(false));
			}
		}
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
