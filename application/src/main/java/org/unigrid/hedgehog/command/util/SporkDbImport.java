/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
    the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.command.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/* A node takes nothing of its own database that the board did not sign, so only a database that holds no such spork
   is installed. The node takes no lock on its database, which it holds in memory and writes over the file as it
   saves, so the command can only say that it has to be stopped. The database it replaces is copied aside first, and
   the new one moved over it in one step. */
@Command(name = "import",
	description = "Installs a spork database that two current board keys signed as the spork database of the node"
		+ " of this machine, and keeps the one it replaces beside it as spork.db.<epoch seconds>. Stop the node"
		+ " first: it keeps its sporks in memory and writes them over the file when it saves. Exits with 1 when"
		+ " the database holds a spork that is not signed by the board or does not hold, and with 2 when it cannot"
		+ " be read or installed."
)
public class SporkDbImport implements Callable<Integer> {
	@Parameters(index = "0", paramLabel = "<file>", description = "The signed spork database to install.")
	private Path file;

	@Spec private CommandSpec spec;

	private final Supplier<Path> dataDirectory;

	public SporkDbImport() {
		this(() -> ApplicationDirectory.create().getUserDataDir());
	}

	SporkDbImport(Supplier<Path> dataDirectory) {
		this.dataDirectory = dataDirectory;
	}

	@Override
	public Integer call() {
		System.out.println("note: stop the node of this machine before the import, as it keeps its sporks in memory"
			+ " and writes them over the file when it saves");

		final SporkDatabase database = SporkDatabaseFile.load(spec, file);

		if (!SporkDatabaseFile.report(database)) {
			System.err.println("Nothing was imported: two current board keys did not sign every spork of " + file
				+ ", or the content of one does not hold");
			return 1;
		}

		final Path target = dataDirectory.get().resolve(SporkDatabase.SPORK_DB_FILE);

		try {
			Files.createDirectories(target.getParent());
			System.out.println("backup: " + backup(target).map(Path::toString)
				.orElse("none, the node holds no spork database"));
			SporkDatabase.persist(target, database);
		} catch (IOException ex) {
			throw new ParameterException(spec.commandLine(), "Cannot install the spork database as " + target
				+ ": " + ex, ex);
		}

		System.out.println("imported: " + target);
		return 0;
	}

	/* A copy, so that the node keeps its database should the install fail */
	private static Optional<Path> backup(Path target) throws IOException {
		if (!Files.exists(target)) {
			return Optional.empty();
		}

		return Optional.of(Files.copy(target, target.resolveSibling(SporkDatabase.SPORK_DB_FILE + "."
			+ Instant.now().getEpochSecond())));
	}
}
