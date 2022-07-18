/*
    Unigrid Hedgehog 
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.command.cli;

import lombok.Getter;
import org.unigrid.hedgehog.command.cli.spork.MintSupply;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "gridspork-set", subcommands = { MintSupply.class },
	description = "Set an existing spork. Redefines any existing definitions of the spork."
)
public class GridSporkSet {
	@Getter @Option(names = { "-D", "--data" }, scope = CommandLine.ScopeType.INHERIT,
		description = "JSON describing the spork data.", required = true
	)
	private static String data;

	@Getter @Option(names = { "-k", "--key" }, scope = CommandLine.ScopeType.INHERIT,
		description = "Hex representation of private key signing the spork.", required = true
	)
	private static String key;
}
