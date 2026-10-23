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

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "validate",
	description = "Prints each spork of a spork database with its digest, who signed it and whether its content"
		+ " holds. Exits with 0 when two current board keys signed every spork in it and the content of each"
		+ " holds, with 1 when not, and with 2 when the file cannot be read."
)
public class SporkDbValidate implements Callable<Integer> {
	@Parameters(index = "0", paramLabel = "<file>", description = "The spork database to check.")
	private Path file;

	@Spec private CommandSpec spec;

	@Override
	public Integer call() {
		return SporkDatabaseFile.report(SporkDatabaseFile.load(spec, file)) ? 0 : 1;
	}
}
