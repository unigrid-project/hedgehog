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

package org.unigrid.hedgehog.command.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.model.bootstrap.BlockFixture;
import org.unigrid.hedgehog.model.bootstrap.SnapshotBuilder;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDigest;
import org.unigrid.hedgehog.model.bootstrap.SnapshotSignature;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import picocli.CommandLine;

public class BootstrapHistoryTest {
	@Example
	@SneakyThrows
	public void shouldRefuseAnUnverifiableSnapshotWithoutAStackTrace() {
		final Path snapshot = refusedSnapshot();
		final CommandLine cli = new CommandLine(Hedgehog.class);
		final ByteArrayOutputStream captured = new ByteArrayOutputStream();
		final PrintStream original = System.err;
		final int exitCode;

		System.setErr(new PrintStream(captured));

		try {
			exitCode = cli.execute("bootstrap", "history", "some-address", "-s", snapshot.toString());
		} finally {
			System.setErr(original);
		}

		final String output = captured.toString();

		assertThat(exitCode, equalTo(2));
		assertThat(output, containsString("does not verify against any trusted key"));
		assertThat(output, not(containsString("\tat ")));
	}

	@SneakyThrows
	private static Path refusedSnapshot() {
		final Path path = snapshot();
		final Signature key = trustedKey();

		SnapshotSignature.signAndAppend(path, key.getPrivateKey());

		final byte[] contents = Files.readAllBytes(path);

		contents[(int) SnapshotDigest.contentLengthOf(path) - 1] ^= 0x01;
		Files.write(path, contents);

		return path;
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
		final Path path = Files.createTempFile("hhg-history-test-", ".dat");

		path.toFile().deleteOnExit();
		SnapshotBuilder.build(BlockFixture.directory(), path);
		return path;
	}
}
