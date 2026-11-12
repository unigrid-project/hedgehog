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
import static org.hamcrest.Matchers.equalTo;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.command.HedgehogCli;
import picocli.CommandLine;

/* The commands run here against the file system of the test as it is, and nothing but the path of the snapshot is
   faked. The file system names a file that is not there by its path alone, so what the commands print is up to them:
   it has to say what failed and why. */
public class BootstrapMissingFileTest {
	private static final String ADDRESS = "HAddress";

	private Path snapshot;

	@BeforeTry
	public void beforeTry() {
		snapshot = BootstrapCli.snapshotInMemory();
	}

	private void assertNamesTheMissingFile(HedgehogCli.Result result, int exitCode, Path file) {
		assertThat(result.err().lines().toList(),
			equalTo(List.of("Cannot access " + file + ": no such file or directory")));
		assertThat(result.exitCode(), equalTo(exitCode));
		result.assertNoStackTrace();
	}

	@Example
	public void shouldSayThatTheSnapshotIsMissingWhenShowingIt() {
		assertNamesTheMissingFile(BootstrapCli.run("info"), ExitCode.UNUSABLE, snapshot);
	}

	@Example
	public void shouldSayThatTheSnapshotIsMissingWhenAskedForABalance() {
		assertNamesTheMissingFile(BootstrapCli.run("balance", ADDRESS), ExitCode.UNUSABLE, snapshot);
	}

	@Example
	public void shouldSayThatTheSnapshotIsMissingWhenAskedForAHistory() {
		assertNamesTheMissingFile(BootstrapCli.run("history", ADDRESS), ExitCode.UNUSABLE, snapshot);
	}

	@Example
	public void shouldSayThatTheBlockDirectoryIsMissing() {
		final FileSystem fs = snapshot.getFileSystem();
		final HedgehogCli.Result result = HedgehogCli.run(() -> new CommandLine(Hedgehog.class)
			.registerConverter(Path.class, fs::getPath), "bootstrap", "import", "-b", "/missing");

		assertThat(result.out(), equalTo(""));
		assertNamesTheMissingFile(result, ExitCode.FAILED, fs.getPath("/missing"));
	}
}
