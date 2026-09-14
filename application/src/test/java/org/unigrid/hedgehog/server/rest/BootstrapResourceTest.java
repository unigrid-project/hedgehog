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

package org.unigrid.hedgehog.server.rest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.bootstrap.AddressBalance;
import org.unigrid.hedgehog.model.bootstrap.AddressTransaction;
import org.unigrid.hedgehog.model.bootstrap.Chain;
import org.unigrid.hedgehog.model.bootstrap.Coin;
import org.unigrid.hedgehog.model.bootstrap.EntryKind;
import org.unigrid.hedgehog.model.bootstrap.Hashing;
import org.unigrid.hedgehog.model.bootstrap.LedgerEntries;
import org.unigrid.hedgehog.model.bootstrap.AddressRegistry;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.bootstrap.Ledger;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInfo;
import org.unigrid.hedgehog.model.bootstrap.SnapshotWriter;
import org.unigrid.hedgehog.model.bootstrap.TransactionIdTable;

public class BootstrapResourceTest extends BaseRestClientTest {
	private static final int HEIGHTS = 4;
	private static final long AMOUNT = 123_456_789L;
	private static final byte[] ADDRESS_HASH = new byte[Hashing.ADDRESS_HASH_SIZE];
	private static final byte[] ABSENT_HASH = absentHash();

	@Example
	@SneakyThrows
	public void shouldReportWhatTheSnapshotHolds() {
		writeSnapshot();

		final SnapshotInfo info = client.getEntity("/bootstrap", SnapshotInfo.class);

		assertThat(info.getTipHeight(), equalTo(HEIGHTS - 1));
		assertThat(info.getAddressCount(), equalTo(1));
		assertThat(info.getEntryCount(), equalTo((long) HEIGHTS));
	}

	@Example
	@SneakyThrows
	public void shouldReturnTheBalanceOfAKnownAddress() {
		writeSnapshot();

		final AddressBalance balance = client.getEntity("/bootstrap/address/"
			+ LegacyAddress.encode(ADDRESS_HASH), AddressBalance.class);

		assertThat(balance.getBalance(), equalTo(Coin.toDecimal(AMOUNT * HEIGHTS)));
		assertThat(balance.getTransactionCount(), equalTo(HEIGHTS));
	}

	@Example
	@SneakyThrows
	public void shouldNotFindAnAddressOutsideTheSnapshot() {
		writeSnapshot();

		final Response response = client.get("/bootstrap/address/" + LegacyAddress.encode(ABSENT_HASH));

		assertThat(response.getStatus(), equalTo(Response.Status.NOT_FOUND.getStatusCode()));
	}

	/* The client tolerates a missing address but treats a rejected one as an oddity worth raising. */
	@Example
	@SneakyThrows
	public void shouldRejectAnAddressThatIsNotAnAddress() {
		writeSnapshot();

		try {
			client.get("/bootstrap/address/NotAnAddress");
			throw new AssertionError("A malformed address was accepted");

		} catch (ResponseOddityException expected) {
			assertThat(expected.getMessage(), startsWith(
				String.valueOf(Response.Status.BAD_REQUEST.getStatusCode())));
		}
	}

	@Example
	@SneakyThrows
	public void shouldPageThroughTheHistoryOfAnAddress() {
		writeSnapshot();

		final String path = "/bootstrap/address/" + LegacyAddress.encode(ADDRESS_HASH) + "/transactions";
		final List<AddressTransaction> page = client.get(path + "?offset=1&limit=2")
			.readEntity(new GenericType<List<AddressTransaction>>() { });

		assertThat(page.size(), equalTo(2));
		assertThat(page.get(0).getHeight(), equalTo(1));
		assertThat(page.get(1).getHeight(), equalTo(2));
		assertThat(page.get(0).getAmount().signum(), greaterThan(0));
	}

	@SneakyThrows
	private static void writeSnapshot() {
		final Path path = ApplicationDirectory.create().getUserDataDir()
			.resolve(SnapshotOptions.SNAPSHOT_FILE);

		if (Files.exists(path)) {
			return;
		}

		SnapshotWriter.write(path, chain(), ledger());
	}

	private static Ledger ledger() {
		final AddressRegistry addresses = new AddressRegistry();
		final LedgerEntries entries = new LedgerEntries(HEIGHTS);
		final TransactionIdTable transactionIds = new TransactionIdTable();
		final int address = addresses.idOf(ADDRESS_HASH);

		for (int height = 0; height < HEIGHTS; height++) {
			final byte[] identifier = new byte[Hashing.HASH_SIZE];

			identifier[0] = (byte) height;
			entries.add(address, AMOUNT, height, transactionIds.add(identifier), EntryKind.RECEIVED);
		}

		return Ledger.builder().addresses(addresses).entries(entries).transactionIds(transactionIds)
			.balances(new long[] { AMOUNT * HEIGHTS }).totalUnspent(AMOUNT * HEIGHTS).build();
	}

	private static Chain chain() {
		final int[] empty = new int[HEIGHTS];
		final int[] times = new int[HEIGHTS];

		for (int height = 0; height < HEIGHTS; height++) {
			times[height] = 1536062400 + height;
		}

		return new Chain(empty, empty, empty, times, new byte[Hashing.HASH_SIZE], HEIGHTS);
	}

	private static byte[] absentHash() {
		final byte[] hash = new byte[Hashing.ADDRESS_HASH_SIZE];

		hash[0] = 0x42;
		return hash;
	}
}
