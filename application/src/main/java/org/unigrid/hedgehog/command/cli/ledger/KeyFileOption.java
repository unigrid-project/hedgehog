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

package org.unigrid.hedgehog.command.cli.ledger;

import java.nio.file.Path;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFile;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;

/* The key file whose seed signs what a command sends to the node */
final class KeyFileOption {
	@Option(names = "--key", required = true, paramLabel = "<file>",
		description = "The key file that holds the seed that signs the ${COMMAND-NAME}."
	)
	private Path keyFile;

	byte[] seed(CommandSpec spec) {
		try {
			return LedgerKeyFile.load(keyFile);
		} catch (IllegalArgumentException ex) {
			throw new ParameterException(spec.commandLine(), "--key: " + ex.getMessage());
		}
	}
}
