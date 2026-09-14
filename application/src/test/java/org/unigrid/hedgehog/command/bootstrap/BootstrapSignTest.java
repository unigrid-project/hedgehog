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

package org.unigrid.hedgehog.command.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.model.bootstrap.BlockFixture;
import org.unigrid.hedgehog.model.bootstrap.SignatureStatus;
import org.unigrid.hedgehog.model.bootstrap.SnapshotBuilder;
import org.unigrid.hedgehog.model.bootstrap.SnapshotSignature;
import org.unigrid.hedgehog.model.crypto.Signature;
import picocli.CommandLine;

public class BootstrapSignTest {
	@Example
	@SneakyThrows
	public void shouldRejectAnUntrustedKeyWithoutCrashing() {
		final Path snapshot = createSnapshot();
		final Signature untrustedKey = new Signature();
		final CommandLine cli = new CommandLine(Hedgehog.class);
		final PrintStream originalErr = System.err;
		final ByteArrayOutputStream err = new ByteArrayOutputStream();
		final int exitCode;

		System.setErr(new PrintStream(err));

		try {
			exitCode = cli.execute("bootstrap", "sign",
				"-s", snapshot.toString(),
				"-k", untrustedKey.getPrivateKey()
			);
		} finally {
			System.setErr(originalErr);
		}

		assertThat(exitCode, equalTo(2));
		assertThat(err.toString(), containsString("not one the network trusts"));
		assertThat(SnapshotSignature.read(snapshot).getStatus(), equalTo(SignatureStatus.UNSIGNED));
	}

	@SneakyThrows
	private static Path createSnapshot() {
		final Path path = Files.createTempFile("hhg-sign-test-", ".dat");

		path.toFile().deleteOnExit();
		SnapshotBuilder.build(BlockFixture.directory(), path);
		return path;
	}
}
