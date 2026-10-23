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

package org.unigrid.hedgehog.command.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.model.crypto.ActiveKeys;
import org.unigrid.hedgehog.model.spork.BoardKeyChain;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/* The way of the board on its own machines: the spork of the board keys with the change is a first version, signed by
   no one, which two keys that the change makes active sign with util spork-db sign, as the other sporks of the file */
@Command(name = "apply",
	description = "Applies a change of the board keys that the board approved to a spork database and writes the"
		+ " result to --output. The spork of the board keys in it is a first version that no one signed: two keys"
		+ " that the change makes active sign it with util spork-db sign, before util spork-db import installs it."
)
public class BoardKeysApply implements Callable<Integer> {
	@Parameters(index = "0", paramLabel = "<file>", description = "The spork database to apply the change to.")
	private Path file;

	@Option(names = "--change", paramLabel = "<file>", required = true,
		description = "The change file that the board approved with util board-keys approve."
	)
	private Path changeFile;

	@Option(names = "--output", paramLabel = "<file>", required = true,
		description = "Where to write the spork database with the change; the file must not exist."
	)
	private Path output;

	@Spec private CommandSpec spec;

	@Override
	public Integer call() {
		final SporkDatabase database = SporkDatabaseFile.load(spec, file);
		final List<KeyChange> chain = new ArrayList<>(changesOf(database.getBoardKeys()));
		final ActiveKeys keys;

		chain.add(ChangeFile.onTheChain(spec, List.copyOf(chain), ChangeFile.read(spec, changeFile)).getChange());

		try {
			keys = BoardKeyChain.verify(chain);
		} catch (IllegalArgumentException ex) {
			throw new ParameterException(spec.commandLine(), "Nothing was applied: " + ex.getMessage(), ex);
		}

		final BoardKeys changed = new BoardKeys();

		changed.<BoardKeys.SporkData>getData().setChanges(chain);
		database.set(GridSpork.firstVersionOf(changed));
		SporkDatabaseFile.writeNew(spec, output, () -> database);
		System.out.println("board keys: " + chain.size() + " changes");
		keys.active().forEach(key -> System.out.println("active: " + key));
		System.out.println("output: " + output);
		return 0;
	}

	private static List<KeyChange> changesOf(BoardKeys held) {
		return Objects.isNull(held) ? List.of() : held.<BoardKeys.SporkData>getData().getChanges();
	}
}
