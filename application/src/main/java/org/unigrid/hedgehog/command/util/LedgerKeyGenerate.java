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

import java.io.IOException;
import java.nio.file.Path;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "ledger-key-generate",
	description = "Makes the key file of a ledger validator and prints its public key, as put in a genesis."
)
public class LedgerKeyGenerate implements Runnable {
	@Parameters(index = "0", paramLabel = "<file>", description = "Where to write the key; the file must not exist.")
	private Path file;

	@Spec private CommandSpec spec;

	@Override
	public void run() {
		try {
			LedgerKeyFile.create(file);
			System.out.println(LedgerKeyFile.publicKeyHex(LedgerKeyFile.load(file)));

		} catch (IOException ex) {
			final String reason = "Cannot create the ledger key file " + file + ": " + ex;

			throw new ParameterException(spec.commandLine(), reason, ex);
		}
	}
}
