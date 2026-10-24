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
import org.unigrid.hedgehog.ledger.ExclusiveLock;
import org.unigrid.hedgehog.ledger.FileLockedException;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkDatabaseLock;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/* A node takes nothing of its own database that the board did not sign, so only a database that holds no such spork
   is installed. A running node holds its database in memory and writes it over the file as it saves, so the import
   refuses while the node holds the lock of its database; a node of an earlier release takes none, and has to be
   stopped all the same. The database it replaces is copied aside first, and the new one moved over it in one step.
   A node that holds no database has no chain of board keys to judge the file by, so the chain of the file is judged
   by the record of this machine too, as the other util commands judge theirs (BoardKeysRecord). */
@Command(name = "import",
	description = "Installs a spork database that two current board keys signed as the spork database of the node"
		+ " of this machine, and keeps the one it replaces beside it as spork.db.<epoch seconds>. Stop the node"
		+ " first: it keeps its sporks in memory and writes them over the file when it saves. Exits with 1 when"
		+ " the database holds a spork that is not signed by the board or does not hold, and with 2 when it cannot"
		+ " be read or installed, or holds less of the chain of board keys than this machine verified before, or"
		+ " another chain."
)
public class SporkDbImport implements Callable<Integer> {
	@Parameters(index = "0", paramLabel = "<file>", description = "The signed spork database to install.")
	private Path file;

	@Spec private CommandSpec spec;

	private final Supplier<Path> dataDirectory;
	private final ExclusiveLock.Locker locker;

	public SporkDbImport() {
		this(() -> ApplicationDirectory.create().getUserDataDir(), ExclusiveLock.SYSTEM);
	}

	SporkDbImport(Supplier<Path> dataDirectory, ExclusiveLock.Locker locker) {
		this.dataDirectory = dataDirectory;
		this.locker = locker;
	}

	@Override
	public Integer call() {
		System.out.println("note: stop the node of this machine before the import, as it keeps its sporks in memory"
			+ " and writes them over the file when it saves");

		final SporkDatabase database = SporkDatabaseFile.load(spec, file);
		final Path target = dataDirectory.get().resolve(SporkDatabase.SPORK_DB_FILE);

		if (!carriesOnTheChainOf(target, database)) {
			System.err.println("Nothing was imported: the board keys of " + file + " do not carry on the"
				+ " chain of board keys of the node, which would take the node back to keys that the board"
				+ " switched off, or onto a fork");
			return 1;
		}

		SporkDatabaseFile.requireRecorded(spec, database, file);

		if (!SporkDatabaseFile.report(database)) {
			System.err.println("Nothing was imported: two current board keys did not sign every spork of " + file
				+ ", or the content of one does not hold");
			return 1;
		}

		try (ExclusiveLock lock = SporkDatabaseLock.take(dataDirectory.get(), locker)) {
			System.out.println("backup: " + backup(target).map(Path::toString)
				.orElse("none, the node holds no spork database"));
			SporkDatabase.persist(target, SporkDatabaseFile.readBack(database));
		} catch (FileLockedException ex) {
			throw new ParameterException(spec.commandLine(), "Nothing was imported: " + ex.getMessage()
				+ "; stop the node of this machine first", ex);
		} catch (IOException ex) {
			throw new ParameterException(spec.commandLine(), "Cannot install the spork database as " + target
				+ ": " + ex, ex);
		}

		System.out.println("imported: " + target);
		return 0;
	}

	/* A node goes by the chain of board keys of its database, so one whose chain does not hold the chain that the node
	   holds would take the node back to keys that the board switched off, or onto a fork. A database of the node that
	   cannot be read, or whose chain does not hold, holds no chain the node goes by. */
	private static boolean carriesOnTheChainOf(Path target, SporkDatabase database) {
		final Optional<BoardKeys> held = boardKeysHeldIn(target);

		return held.isEmpty()
			|| database.getBoardKeys() instanceof BoardKeys keys && keys.holdsTheChainOf(held.get());
	}

	private static Optional<BoardKeys> boardKeysHeldIn(Path target) {
		try {
			return Files.exists(target) ? Optional.ofNullable(SporkDatabase.load(target).getBoardKeys())
				.filter(keys -> keys.chainKeys().isPresent()) : Optional.empty();
		} catch (IOException | RuntimeException ex) {
			return Optional.empty();
		}
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
