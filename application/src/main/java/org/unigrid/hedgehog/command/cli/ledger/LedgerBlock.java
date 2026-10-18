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
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "block", description = "Show the block at a height of the chain, with its transactions.")
public class LedgerBlock implements Callable<Integer> {
	@Spec
	private CommandSpec spec;

	@Parameters(index = "0", paramLabel = "<height>", description = "The height of the block, from 1 up to the tip.")
	private String height;

	@Override
	public Integer call() {
		final long requested = requested();

		return LedgerClient.print(ledger -> ledger.block(requested));
	}

	private long requested() {
		try {
			final long parsed = Long.parseLong(height);

			if (parsed >= 1) {
				return parsed;
			}
		} catch (NumberFormatException ex) {
			/* Refused below, in the same words as a height under 1 */
		}

		throw new ParameterException(spec.commandLine(),
			"\"%s\" is not a height: a height is a whole number from 1 up to the tip".formatted(height));
	}
}
