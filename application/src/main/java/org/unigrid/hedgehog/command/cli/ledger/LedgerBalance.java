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

import java.util.concurrent.Callable;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "balance",
	description = "Show the balance of an account or a legacy address, and what a legacy address can still claim."
)
public class LedgerBalance implements Callable<Integer> {
	@Spec
	private CommandSpec spec;

	@Parameters(index = "0", paramLabel = "<address>",
		description = "An account as 64 hex characters, or a legacy address."
	)
	private String address;

	@Override
	public Integer call() {
		final String requested = requested();

		return LedgerClient.print(ledger -> ledger.account(requested));
	}

	/* The node knows an account by lower case hex only, so what is asked for is the address in its own spelling */
	private String requested() {
		return LedgerText.typed(address).map(LedgerText::text).orElseThrow(() -> new ParameterException(
			spec.commandLine(), "\"%s\" is neither an account (64 hex characters) nor a legacy address"
				.formatted(address)));
	}
}
