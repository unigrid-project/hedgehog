/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;

public class BootstrapSnapshotTest {
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
