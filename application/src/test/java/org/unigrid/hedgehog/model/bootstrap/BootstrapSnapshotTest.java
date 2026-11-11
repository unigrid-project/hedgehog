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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;

public class BootstrapSnapshotTest {
	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@Example
	@SneakyThrows
	public void shouldServeAnUnsignedSnapshot() {
		final BootstrapSnapshot snapshot = new BootstrapSnapshot(snapshot());

		assertThat(snapshot.getReader().isPresent(), equalTo(true));
		assertThat(snapshot.getReader().orElseThrow().getInfo().getSignature(),
			equalTo(SignatureStatus.UNSIGNED));
	}

	@Example
	@SneakyThrows
	public void shouldServeASignedSnapshot() {
		final Path path = snapshot();
		final Signature key = trustedKey();

		SnapshotSignature.signAndAppend(path, key.getPrivateKey());

		assertThat(new BootstrapSnapshot(path).getReader().orElseThrow().getInfo().getSignature(),
			equalTo(SignatureStatus.SIGNED));
	}

	@Example
	@SneakyThrows
	public void shouldRefuseASnapshotWhoseSignatureDoesNotVerify() {
		final Path path = snapshot();

		SnapshotSignature.signAndAppend(path, trustedKey().getPrivateKey());

		final byte[] contents = Files.readAllBytes(path);

		contents[(int) SnapshotDigest.contentLengthOf(path) - 1] ^= 0x01;
		Files.write(path, contents);

		assertThat(new BootstrapSnapshot(path).getReader().isPresent(), equalTo(false));
	}

	@Example
	@SneakyThrows
	public void shouldPickUpASnapshotReplacedBehindACachedReader() {
		final Path path = snapshot();
		final BootstrapSnapshot bootstrapSnapshot = new BootstrapSnapshot(path);

		assertThat(bootstrapSnapshot.getReader().orElseThrow().getInfo().getSignature(),
			equalTo(SignatureStatus.UNSIGNED));

		SnapshotSignature.signAndAppend(path, trustedKey().getPrivateKey());

		assertThat(bootstrapSnapshot.getReader().orElseThrow().getInfo().getSignature(),
			equalTo(SignatureStatus.SIGNED));
	}

	@Example
	@SneakyThrows
	public void shouldOpenARefusedSnapshotOnlyOnce() {
		final Path path = snapshot();

		SnapshotSignature.signAndAppend(path, trustedKey().getPrivateKey());

		final byte[] contents = Files.readAllBytes(path);

		contents[(int) SnapshotDigest.contentLengthOf(path) - 1] ^= 0x01;
		Files.write(path, contents);

		final AtomicInteger opens = new AtomicInteger();

		new MockUp<SnapshotReader>() {
			@Mock
			public SnapshotReader open(Invocation invocation, Path invocationPath) throws IOException {
				opens.incrementAndGet();
				return invocation.proceed();
			}
		};

		final BootstrapSnapshot snapshot = new BootstrapSnapshot(path);

		assertThat(snapshot.getReader().isPresent(), equalTo(false));
		assertThat(snapshot.getReader().isPresent(), equalTo(false));
		assertThat(opens.get(), equalTo(1));
	}

	/* A node that goes by the board keys of its release refuses a snapshot that a board key signed which joined later.
	   The refusal is a verdict on the board keys as much as on the file, so once the node learns of the key the
	   snapshot is taken without the file being touched. */
	@Example
	@SneakyThrows
	public void shouldJudgeARefusedSnapshotAgainWhenTheBoardKeysChange() {
		buildIn(keys(0, 1));

		final Path path = snapshot();

		SnapshotSignature.signAndAppend(path, key(2).getPrivateKey());

		final BootstrapSnapshot snapshot = new BootstrapSnapshot(path);

		assertThat(snapshot.getReader().isPresent(), equalTo(false));
		assertThat(NetworkKey.install(NetworkKey.getActiveKeys().after(List.of(publicKeysOf(keys(1, 2))))),
			equalTo(true));

		assertThat(snapshot.getReader().isPresent(), equalTo(true));
	}

	@Example
	@SneakyThrows
	public void shouldRefuseASnapshotWhoseHeaderCountsAreOutOfRangeAndPickUpItsReplacement() {
		final Path path = snapshot();
		final byte[] valid = Files.readAllBytes(path);
		final byte[] corrupt = valid.clone();

		ByteBuffer.wrap(corrupt).putInt(SnapshotFormat.ADDRESS_COUNT_OFFSET, 0xFFFFFFFF);
		Files.write(path, corrupt);

		final BootstrapSnapshot snapshot = new BootstrapSnapshot(path);

		assertThat(snapshot.getReader().isPresent(), equalTo(false));
		assertThat(snapshot.getReader().isPresent(), equalTo(false));

		Files.write(path, valid);
		Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 1_000));

		assertThat(snapshot.getReader().isPresent(), equalTo(true));
	}

	/* What is cached is the file as it was when the open began, so a file that changes while it is being opened,
	   which takes seconds for a large snapshot, is seen as changed on the next request. Here a signature is
	   appended after the one of the file was read. */
	@Example
	@SneakyThrows
	public void shouldOpenASnapshotAgainThatWasSignedWhileItWasBeingOpened() {
		final Path path = snapshot();
		final Signature key = trustedKey();
		final AtomicBoolean signed = new AtomicBoolean();

		new MockUp<SnapshotSignature>() {
			@Mock
			@SneakyThrows
			public SnapshotSignature read(Invocation invocation, Path invocationPath) {
				final SnapshotSignature read = invocation.proceed();

				if (!signed.getAndSet(true)) {
					SnapshotSignature.signAndAppend(invocationPath, key.getPrivateKey());
				}

				return read;
			}
		};

		final BootstrapSnapshot snapshot = new BootstrapSnapshot(path);

		assertThat(snapshot.getReader().orElseThrow().getInfo().getSignature(), equalTo(SignatureStatus.UNSIGNED));
		assertThat(snapshot.getReader().orElseThrow().getInfo().getSignature(), equalTo(SignatureStatus.SIGNED));
	}

	/* The same for a refusal: a snapshot that is moved into place while the one it replaces is refused, as
	   `bootstrap fetch --force` does, is not the one that was refused */
	@Example
	@SneakyThrows
	public void shouldOpenAGoodSnapshotThatReplacedARefusedOneWhileItWasBeingOpened() {
		final Path path = snapshot();
		final Path replacement = path.resolveSibling(path.getFileName() + ".new");
		final AtomicBoolean replaced = new AtomicBoolean();

		SnapshotSignature.signAndAppend(path, trustedKey().getPrivateKey());
		Files.copy(path, replacement);
		replacement.toFile().deleteOnExit();

		final byte[] contents = Files.readAllBytes(path);

		contents[(int) SnapshotDigest.contentLengthOf(path) - 1] ^= 0x01;
		Files.write(path, contents);
		Files.setLastModifiedTime(replacement, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 1_000));

		new MockUp<SnapshotSignature>() {
			@Mock
			@SneakyThrows
			public SnapshotSignature read(Invocation invocation, Path invocationPath) {
				final SnapshotSignature read = invocation.proceed();

				if (!replaced.getAndSet(true)) {
					Files.move(replacement, invocationPath, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
				}

				return read;
			}
		};

		final BootstrapSnapshot snapshot = new BootstrapSnapshot(path);

		assertThat(snapshot.getReader().isPresent(), equalTo(false));
		assertThat(snapshot.getReader().isPresent(), equalTo(true));
	}

	/* The channel that maps the tables is closed before the signature is read from the path, so a snapshot that is
	   moved into place in between is signed as the file it replaced is served. The next request has to see that the
	   file is not the one that was mapped. */
	@Example
	@SneakyThrows
	public void shouldMapTheSnapshotThatReplacedOneWhileItsSignatureWasRead() {
		final Path path = snapshot();
		final Path replacement = path.resolveSibling(path.getFileName() + ".new");
		final byte[] bytes = Files.readAllBytes(path);
		final int tipHeight = ByteBuffer.wrap(bytes).getInt(SnapshotFormat.TIP_HEIGHT_OFFSET);
		final Signature key = trustedKey();
		final AtomicBoolean replaced = new AtomicBoolean();

		ByteBuffer.wrap(bytes).putInt(SnapshotFormat.TIP_HEIGHT_OFFSET, tipHeight - 1);
		replacement.toFile().deleteOnExit();
		Files.write(replacement, bytes);
		SnapshotSignature.signAndAppend(replacement, key.getPrivateKey());

		new MockUp<SnapshotSignature>() {
			@Mock
			@SneakyThrows
			public SnapshotSignature read(Invocation invocation, Path invocationPath) {
				if (!replaced.getAndSet(true)) {
					Files.move(replacement, invocationPath, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
				}

				return invocation.proceed();
			}
		};

		final BootstrapSnapshot snapshot = new BootstrapSnapshot(path);

		assertThat(snapshot.getReader().orElseThrow().tipHeight(), equalTo(tipHeight));
		assertThat(snapshot.getReader().orElseThrow().tipHeight(), equalTo(tipHeight - 1));
	}

	@Example
	@SneakyThrows
	public void shouldReportNoReaderWhenThereIsNoSnapshot() {
		assertThat(new BootstrapSnapshot(Path.of("/nonexistent/bootstrap.dat"))
			.getReader().isPresent(), equalTo(false));
	}

	@SneakyThrows
	private static Signature trustedKey() {
		final Signature signature = new Signature();

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { signature.getPublicKey() };
			}
		};

		return signature;
	}

	@SneakyThrows
	private static Path snapshot() {
		final Path path = Files.createTempFile("hhg-policy-", ".dat");

		path.toFile().deleteOnExit();
		SnapshotBuilder.build(BlockFixture.directory(), path);
		return path;
	}
}
