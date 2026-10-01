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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.model.bootstrap.BuildReport;
import org.unigrid.hedgehog.model.bootstrap.SnapshotBuilder;

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
