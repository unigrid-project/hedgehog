/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.command.bootstrap;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import org.unigrid.hedgehog.model.bootstrap.AddressBalance;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "balance", description = "Show the coins held by a legacy address.")
public class BootstrapBalance implements Callable<Integer> {
	@Parameters(index = "0", paramLabel = "<address>", description = "Legacy Unigrid address.")
	private String address;

	@Override
	public Integer call() throws IOException {
		final Optional<AddressBalance> balance;

		try {
			balance = SnapshotReader.open(SnapshotOptions.getSnapshot()).balanceOf(address);

		} catch (IllegalArgumentException ex) {
			System.err.println(ex.getMessage());
			return 2;
		}

		if (balance.isEmpty()) {
			System.err.println(address + " never appeared on the legacy chain");
			return 1;
		}

		System.out.println(balance.get().getBalance() + " in " + balance.get().getTransactionCount()
			+ " transactions");
		return 0;
	}
}
