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

package org.unigrid.hedgehog.command.bootstrap;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.bootstrap.AddressTransaction;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "history", description = "List every transaction that touched a legacy address.")
public class BootstrapHistory implements Callable<Integer> {
	@Parameters(index = "0", paramLabel = "<address>", description = "Legacy Unigrid address.")
	private String address;

	@Option(names = { "-n", "--limit" }, description = "Show at most this many transactions.",
		defaultValue = "100"
	)
	private int limit;

	@Option(names = "--offset", description = "Skip this many transactions first.", defaultValue = "0")
	private int offset;

	@Option(names = "--json", description = "Print the transactions as JSON.")
	private boolean json;

	@Override
	public Integer call() throws IOException {
		final List<AddressTransaction> transactions;

		try {
			transactions = SnapshotReader.open(SnapshotOptions.getSnapshot())
				.transactionsOf(address, offset, limit);

		} catch (IllegalArgumentException ex) {
			System.err.println(ex.getMessage());
			return 2;
		}

		if (transactions.isEmpty()) {
			System.err.println("No transactions for " + address);
			return 1;
		}

		if (json) {
			System.out.println(new JsonConfiguration().getContext(AddressTransaction.class)
				.writerWithDefaultPrettyPrinter().writeValueAsString(transactions));
		} else {
			transactions.forEach(BootstrapHistory::print);
		}

		return 0;
	}

	private static void print(AddressTransaction transaction) {
		System.out.printf("%s  %8d  %-8s %18s  %s%n", transaction.getTime(), transaction.getHeight(),
			transaction.getKind(), transaction.getAmount(), transaction.getTransaction());
	}
}
