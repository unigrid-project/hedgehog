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

package org.unigrid.hedgehog.command.option;

import java.nio.file.Path;
import lombok.Getter;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class LedgerOptions {

	@Getter @Option(names = "--ledger", scope = CommandLine.ScopeType.INHERIT, defaultValue = "false",
		description = "Run the ledger: follow and serve its blocks (off by default)."
	)
	private static boolean ledger;

	@Getter @Option(names = "--ledger-key", scope = CommandLine.ScopeType.INHERIT,
		description = "Validate with the seed in this file, as made by 'util ledger-key-generate' "
		+ "(without it the node follows the ledger and never signs)"
	)
	private static Path ledgerKeyFile;

	@Getter @Option(names = "--ledger-genesis", scope = CommandLine.ScopeType.INHERIT,
		description = "Use the genesis in this file instead of the one that comes with the node. A changed genesis "
		+ "starts a new chain: its block log and sign state are kept in a directory of their own below 'ledger' "
		+ "in the data directory, named by the start of its chain id, and those of the chain before stay there "
		+ "(delete them if that chain is of no use any more)"
	)
	private static Path ledgerGenesisFile;

	@Getter @Option(names = "--ledger-trusted", scope = CommandLine.ScopeType.INHERIT,
		description = "The block a synced ledger must have, as <height>:<128 hexadecimal characters of its hash>"
	)
	private static String ledgerTrusted;
}
