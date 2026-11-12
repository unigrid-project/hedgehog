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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.Lombok;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.model.bootstrap.BuildReport;
import org.unigrid.hedgehog.model.bootstrap.SnapshotBuilder;
import picocli.CommandLine;

public class BootstrapImportTest {
	private static final Path BLOCKS = Path.of("blocks");

	private final List<List<Path>> builds = new ArrayList<>();
	private Path snapshot;

	@BeforeTry
	public void beforeTry() {
		builds.clear();
		snapshot = BootstrapCli.snapshotInMemory();
		builderReports(BuildReport.builder().build());
	}

	private void builderReports(BuildReport report) {
		new MockUp<SnapshotBuilder>() {
			@Mock public /* static */ BuildReport build(Path blocksDirectory, Path output) {
				builds.add(List.of(blocksDirectory, output));
				return report;
			}
		};
	}

	private void builderFails(Throwable failure) {
		new MockUp<SnapshotBuilder>() {
			@Mock public /* static */ BuildReport build(Path blocksDirectory, Path output) {
				builds.add(List.of(blocksDirectory, output));
				throw Lombok.sneakyThrow(failure);
			}
		};
	}

	@SneakyThrows
	private void existingSnapshot() {
		Files.createDirectories(snapshot.getParent());
		Files.write(snapshot, new byte[] { 1 });
	}

	@Provide
	public Arbitrary<BuildReport> provideReport() {
		return BootstrapArbitraries.reports();
	}

	@Property(tries = 30)
	public void shouldPrintTheReportOfTheBuild(@ForAll("provideReport") BuildReport report) {
		builderReports(report);

		final HedgehogCli.Result result = BootstrapCli.run("import", "-b", BLOCKS.toString());

		assertThat(result.exitCode(), equalTo(0));
		assertThat(result.out(), containsString(report.toString()));
		assertThat(result.out(), containsString("Snapshot:           " + snapshot));
		assertThat(builds, equalTo(List.of(List.of(BLOCKS, snapshot))));
	}

	@Property(tries = 30)
	public void shouldWriteTheSnapshotWhereItIsTold(
		@ForAll @Size(max = 4) List<@AlphaChars @StringLength(min = 1, max = 12) String> directories) {

		final FileSystem fs = snapshot.getFileSystem();
		final Path output = fs.getPath("/elsewhere", directories.toArray(String[]::new)).resolve("snapshot.dat");
		final HedgehogCli.Result result = HedgehogCli.run(() -> new CommandLine(Hedgehog.class)
			.registerConverter(Path.class, fs::getPath), "bootstrap", "import", "-b", BLOCKS.toString(),
			"-o", output.toString()
		);

		assertThat(result.exitCode(), equalTo(0));
		assertThat(result.out(), containsString("Snapshot:           " + output));
		assertThat(builds, equalTo(List.of(List.of(fs.getPath(BLOCKS.toString()), output))));
		assertThat(Files.isDirectory(output.getParent()), equalTo(true));
		assertThat(Files.exists(snapshot.getParent()), equalTo(false));
	}

	/* A bare name has no parent directory of its own, so the snapshot goes to the working directory as it does
	   for ./name */
	@Example
	public void shouldWriteTheSnapshotToABareFileName() {
		final FileSystem fs = snapshot.getFileSystem();
		final HedgehogCli.Result result = HedgehogCli.run(() -> new CommandLine(Hedgehog.class)
			.registerConverter(Path.class, fs::getPath), "bootstrap", "import", "-b", BLOCKS.toString(),
			"-o", "snapshot.dat"
		);

		result.assertNoStackTrace();
		assertThat(result.exitCode(), equalTo(0));
		assertThat(result.out(), containsString("Snapshot:           snapshot.dat"));
		assertThat(builds, equalTo(List.of(List.of(fs.getPath(BLOCKS.toString()), fs.getPath("snapshot.dat")))));
		assertThat(Files.exists(snapshot.getParent()), equalTo(false));
	}

	/* What is wrong with the block files, or with the disk the snapshot goes to, is told in a line, as the other
	   bootstrap commands tell it, and not shown as the trace of an exception that came out of the command */
	@Property(tries = 20)
	public void shouldTellInOneLineWhyTheSnapshotCouldNotBeBuilt(@ForAll boolean unwritable,
		@ForAll @AlphaChars @StringLength(min = 1, max = 40) String reason) {

		builderFails(unwritable ? new IOException(reason) : new IllegalStateException(reason));

		final HedgehogCli.Result result = BootstrapCli.run("import", "-b", BLOCKS.toString());

		result.assertNoStackTrace();
		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of(reason)));
		assertThat(result.exitCode(), equalTo(ExitCode.FAILED));
		assertThat(builds, equalTo(List.of(List.of(BLOCKS, snapshot))));
	}

	/* The file system names a directory that the user may not read by its path alone, so the line says what failed and
	   why */
	@Example
	public void shouldSayThatTheBlockDirectoryMayNotBeRead() {
		builderFails(new AccessDeniedException("/blocks"));

		final HedgehogCli.Result result = BootstrapCli.run("import", "-b", "/blocks");

		result.assertNoStackTrace();
		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of("Cannot access /blocks: permission denied")));
		assertThat(result.exitCode(), equalTo(ExitCode.FAILED));
	}

	/* A move that fails names two files: first the partial file, which the user never gave and which is gone by the
	   time the line is read, and then the place the snapshot was to go to. That place is what the line has to name */
	@Example
	public void shouldNameThePlaceTheSnapshotCouldNotBeMovedTo() {
		builderFails(new FileSystemException(snapshot.resolveSibling("bootstrap-build-pid7-1.tmp").toString(),
			snapshot.toString(), "Is a directory"));

		final HedgehogCli.Result result = BootstrapCli.run("import", "-b", BLOCKS.toString());

		result.assertNoStackTrace();
		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of("Cannot write " + snapshot + ": Is a directory")));
		assertThat(result.exitCode(), equalTo(ExitCode.FAILED));
	}

	@Example
	public void shouldCreateTheDirectoryOfTheSnapshot() {
		assertThat(BootstrapCli.run("import", "-b", BLOCKS.toString()).exitCode(), equalTo(0));
		assertThat(Files.isDirectory(snapshot.getParent()), equalTo(true));
	}

	@Example
	public void shouldLeaveAnExistingSnapshotAlone() {
		existingSnapshot();

		final HedgehogCli.Result result = BootstrapCli.run("import", "-b", BLOCKS.toString());

		assertThat(result.exitCode(), equalTo(1));
		assertThat(result.err(), containsString("already exists, pass --force to overwrite it"));
		assertThat(builds, empty());
	}

	@Example
	public void shouldOverwriteAnExistingSnapshotWhenForced() {
		existingSnapshot();

		assertThat(BootstrapCli.run("import", "-b", BLOCKS.toString(), "--force").exitCode(), equalTo(0));
		assertThat(builds, equalTo(List.of(List.of(BLOCKS, snapshot))));
	}
}
