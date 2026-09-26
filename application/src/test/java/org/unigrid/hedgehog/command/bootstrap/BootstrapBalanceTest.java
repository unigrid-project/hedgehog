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
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import mockit.Mock;
import mockit.MockUp;
import java.util.Optional;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.model.bootstrap.AddressBalance;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;

public class BootstrapBalanceTest {
	private static final String ADDRESS = "HAddress";

	@BeforeTry
	public void beforeTry() {
		BootstrapCli.snapshotInMemory();
	}

	/* Any other address than the expected one never appeared, so a command asking for it fails */
	private void readerAnswers(String address, Optional<AddressBalance> balance, RuntimeException problem,
		IOException unreadable) {

		final SnapshotReader reader = BootstrapCli.withoutConstructor(SnapshotReader.class);

		new MockUp<SnapshotReader>() {
			@Mock public /* static */ SnapshotReader open(Path path) throws IOException {
				if (unreadable != null) {
					throw unreadable;
				}

				return reader;
			}

			@Mock public Optional<AddressBalance> balanceOf(String requested) {
				if (problem != null) {
					throw problem;
				}

				return requested.equals(address) ? balance : Optional.empty();
			}
		};
	}

	@Provide
	public Arbitrary<String> provideAddress() {
		return BootstrapArbitraries.addresses();
	}

	@Provide
	public Arbitrary<BigDecimal> provideCoins() {
		return BootstrapArbitraries.coins();
	}

	@Property(tries = 50)
	public void shouldPrintTheBalanceOfTheAddress(@ForAll("provideAddress") String address,
		@ForAll("provideCoins") BigDecimal balance, @ForAll @IntRange(min = 0) int transactions) {

		readerAnswers(address, Optional.of(new AddressBalance(address, balance, transactions)), null, null);

		final BootstrapCli.Result result = BootstrapCli.run("balance", address);

		assertThat(result.exitCode(), equalTo(0));
		assertThat(result.out(), equalTo(balance + " in " + transactions + " transactions" + System.lineSeparator()));
	}

	@Example
	public void shouldReportAnAddressThatNeverAppeared() {
		readerAnswers(ADDRESS, Optional.empty(), null, null);

		final BootstrapCli.Result result = BootstrapCli.run("balance", ADDRESS);

		assertThat(result.exitCode(), equalTo(1));
		assertThat(result.err(), containsString(ADDRESS + " never appeared on the legacy chain"));
	}

	@Example
	public void shouldRefuseAMalformedAddressWithoutAStackTrace() {
		readerAnswers(ADDRESS, Optional.empty(), new IllegalArgumentException("Not a legacy address"), null);

		final BootstrapCli.Result result = BootstrapCli.run("balance", ADDRESS);

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("Not a legacy address"));
		result.assertNoStackTrace();
	}

	@Example
	public void shouldRefuseAnUnreadableSnapshotWithoutAStackTrace() {
		readerAnswers(ADDRESS, Optional.empty(), null, new IOException("does not verify against any trusted key"));

		final BootstrapCli.Result result = BootstrapCli.run("balance", ADDRESS);

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("does not verify against any trusted key"));
		result.assertNoStackTrace();
	}
}
