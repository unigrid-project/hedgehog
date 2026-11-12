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
import java.util.Optional;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.model.option.SnapshotOptions;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.bootstrap.AddressBalance;
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
		final SnapshotReader reader;
		final List<AddressTransaction> transactions;

		try {
			reader = SnapshotReader.open(SnapshotOptions.getSnapshot());
			transactions = reader.transactionsOf(address, offset, limit);

		} catch (IllegalArgumentException | IOException ex) {
			System.err.println(FileFailure.describe(ex));
			return ExitCode.UNUSABLE;
		}

		if (transactions.isEmpty()) {
			return reportNone(reader);
		}

		if (json) {
			System.out.println(new JsonConfiguration().getContext(AddressTransaction.class)
				.writerWithDefaultPrettyPrinter().writeValueAsString(transactions));
		} else {
			transactions.forEach(BootstrapHistory::print);
		}

		return ExitCode.OK;
	}

	/* An address that appeared has transactions even where this page of them is empty, as one that is asked for past
	   its last transaction or by none at all */
	private int reportNone(SnapshotReader reader) {
		final Optional<AddressBalance> balance = reader.balanceOf(address);

		if (balance.isPresent()) {
			System.err.println("None of the transactions of " + address + " ("
				+ balance.get().getTransactionCount() + " in all) lies within --offset " + offset
				+ " and --limit " + limit);
		} else {
			System.err.println("No transactions for " + address);
		}

		return ExitCode.FAILED;
	}

	private static void print(AddressTransaction transaction) {
		System.out.printf("%s  %8d  %-8s %18s  %s%n", transaction.getTime(), transaction.getHeight(),
			transaction.getKind(), transaction.getAmount(), transaction.getTransaction());
	}
}
