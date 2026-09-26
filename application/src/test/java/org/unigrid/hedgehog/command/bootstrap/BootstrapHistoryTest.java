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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.nio.file.Path;
import mockit.Mock;
import mockit.MockUp;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.bootstrap.AddressTransaction;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;

public class BootstrapHistoryTest {
	private static final String ADDRESS = "HAddress";
	private static final int DEFAULT_OFFSET = 0;
	private static final int DEFAULT_LIMIT = 100;
	private static final int MAX_PAGE = 1000;

	@BeforeTry
	public void beforeTry() {
		BootstrapCli.snapshotInMemory();
	}

	/* Answers only these exact arguments, so a command passing anything else gets an empty page */
	private void readerAnswers(String address, int offset, int limit, List<AddressTransaction> page,
		RuntimeException problem, IOException unreadable) {

		final SnapshotReader reader = BootstrapCli.withoutConstructor(SnapshotReader.class);
		final List<Object> expected = List.of(address, offset, limit);

		new MockUp<SnapshotReader>() {
			@Mock public /* static */ SnapshotReader open(Path path) throws IOException {
				if (unreadable != null) {
					throw unreadable;
				}

				return reader;
			}

			@Mock public List<AddressTransaction> transactionsOf(String requested, int from, int count) {
				if (problem != null) {
					throw problem;
				}

				return expected.equals(List.of(requested, from, count)) ? page : List.of();
			}
		};
	}

	@Provide
	public Arbitrary<String> provideAddress() {
		return BootstrapArbitraries.addresses();
	}

	@Provide
	public Arbitrary<List<AddressTransaction>> provideTransactions() {
		return BootstrapArbitraries.transactions();
	}

	@Property(tries = 30)
	public void shouldPrintOneLinePerTransaction(@ForAll("provideAddress") String address,
		@ForAll @IntRange(max = MAX_PAGE) int offset, @ForAll @IntRange(min = 1, max = MAX_PAGE) int limit,
		@ForAll("provideTransactions") List<AddressTransaction> transactions) {

		readerAnswers(address, offset, limit, transactions, null, null);

		final BootstrapCli.Result result = BootstrapCli.run("history", address,
			"--offset", String.valueOf(offset), "-n", String.valueOf(limit)
		);

		final List<String> lines = result.out().lines().toList();

		assertThat(result.exitCode(), equalTo(0));
		assertThat(lines, hasSize(transactions.size()));

		for (int i = 0; i < transactions.size(); i++) {
			assertThat(lines.get(i), containsString(transactions.get(i).getTransaction()));
		}
	}

	@Property(tries = 30)
	public void shouldPrintTheTransactionsAsJson(@ForAll("provideAddress") String address,
		@ForAll("provideTransactions") List<AddressTransaction> transactions) throws IOException {

		readerAnswers(address, DEFAULT_OFFSET, DEFAULT_LIMIT, transactions, null, null);

		final BootstrapCli.Result result = BootstrapCli.run("history", address, "--json");

		assertThat(result.exitCode(), equalTo(0));
		assertThat(new JsonConfiguration().getContext(AddressTransaction.class).readValue(result.out(),
			new TypeReference<List<AddressTransaction>>() { }), equalTo(transactions)
		);
	}

	@Example
	public void shouldReportAnAddressWithoutTransactions() {
		readerAnswers(ADDRESS, DEFAULT_OFFSET, DEFAULT_LIMIT, List.of(), null, null);

		final BootstrapCli.Result result = BootstrapCli.run("history", ADDRESS);

		assertThat(result.exitCode(), equalTo(1));
		assertThat(result.err(), containsString("No transactions for " + ADDRESS));
	}

	@Example
	public void shouldRefuseAMalformedAddressWithoutAStackTrace() {
		readerAnswers(ADDRESS, DEFAULT_OFFSET, DEFAULT_LIMIT, List.of(), new IllegalArgumentException("Not a legacy address"),
			null
		);

		final BootstrapCli.Result result = BootstrapCli.run("history", ADDRESS);

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("Not a legacy address"));
		result.assertNoStackTrace();
	}

	@Example
	public void shouldRefuseAnUnreadableSnapshotWithoutAStackTrace() {
		readerAnswers(ADDRESS, DEFAULT_OFFSET, DEFAULT_LIMIT, List.of(), null,
			new IOException("does not verify against any trusted key")
		);

		final BootstrapCli.Result result = BootstrapCli.run("history", ADDRESS);

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("does not verify against any trusted key"));
		result.assertNoStackTrace();
	}
}
