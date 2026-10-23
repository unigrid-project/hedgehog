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

package org.unigrid.hedgehog.command.cli.spork;

import java.math.BigDecimal;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.LedgerFee.SporkData;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParameterException;

@Command(name = "ledger-fee",
	description = "The minimum fee of a transfer and the minimum amount of a transfer that opens an account; to set "
		+ "them, the data is the fee in coins, like 0.001, or the fee and the amount, like 0.001:1."
)
public class LedgerFee extends SporkCommand<SporkData> {
	@Override
	protected String location() {
		return "/gridspork/ledger-fee";
	}

	@Override
	protected GridSpork.Type type() {
		return GridSpork.Type.LEDGER_FEE;
	}

	@Override
	protected void apply(final SporkData data, final GridSpork spork) {
		spork.setData(data);
	}

	/* A BigDecimal keeps the typed digits, so no floating point ever rounds the fee on its way to the daemon */
	@Override
	protected SporkData read(final String text) {
		try {
			final SporkData data = new SporkData();
			final String[] amounts = text.split(":", -1);

			if (amounts.length > 2) {
				throw new IllegalArgumentException(
					"give the fee, or the fee and the amount to a new account");
			}

			data.setMinimumTransferFee(new BigDecimal(amounts[0].strip()));

			if (amounts.length == 2) {
				data.setMinimumNewAccountAmount(new BigDecimal(amounts[1].strip()));
			}

			data.validate();
			return data;
		} catch (IllegalArgumentException ex) {
			throw new ParameterException(spec().commandLine(), "The fee \"%s\" is not accepted: %s"
				.formatted(text, ex.getMessage()), ex);
		}
	}
}
