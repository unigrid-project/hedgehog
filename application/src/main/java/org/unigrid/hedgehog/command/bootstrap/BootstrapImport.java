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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import org.unigrid.hedgehog.model.bootstrap.SnapshotBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "import",
	description = "Read the legacy blk*.dat files and write a snapshot of every address and its history."
)
public class BootstrapImport implements Callable<Integer> {
	@Option(names = { "-b", "--blocks" }, required = true,
		description = "Directory holding the legacy blk*.dat block files."
	)
	private Path blocks;

	@Option(names = { "-o", "--output" }, description = "Snapshot file to write.")
	private Path output;

	@Option(names = "--force", description = "Overwrite the snapshot file if it already exists.")
	private boolean force;

	@Override
	public Integer call() throws IOException {
		final Path target = output != null ? output : SnapshotOptions.defaultSnapshot();

		if (Files.exists(target) && !force) {
			System.err.println(target + " already exists, pass --force to overwrite it");
			return 1;
		}

		Files.createDirectories(target.toAbsolutePath().getParent());
		System.out.println(SnapshotBuilder.build(blocks, target));
		System.out.println("Snapshot:           " + target);
		return 0;
	}
}
