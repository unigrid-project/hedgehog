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
import static org.hamcrest.Matchers.startsWith;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.model.option.SnapshotOptions;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.AddressBalance;
import org.unigrid.hedgehog.model.bootstrap.AddressTransaction;
import org.unigrid.hedgehog.model.bootstrap.BlockParser;
import org.unigrid.hedgehog.model.bootstrap.Chain;
import org.unigrid.hedgehog.model.bootstrap.Coin;
import org.unigrid.hedgehog.model.bootstrap.EntryKind;
import org.unigrid.hedgehog.model.bootstrap.Hashing;
import org.unigrid.hedgehog.model.bootstrap.Holder;
import org.unigrid.hedgehog.model.bootstrap.LedgerEntries;
import org.unigrid.hedgehog.model.bootstrap.AddressRegistry;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.bootstrap.Ledger;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInfo;
import org.unigrid.hedgehog.model.bootstrap.SnapshotWriter;
import org.unigrid.hedgehog.model.bootstrap.TransactionIdTable;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.LegacyWhiteList;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.rest.entity.HeightResponse;

public class BootstrapResourceTest extends BaseRestClientTest {
	private static final int HEIGHTS = 4;
	private static final long AMOUNT = 123_456_789L;
	private static final byte[] ADDRESS_HASH = new byte[Hashing.ADDRESS_HASH_SIZE];
	private static final byte[] ABSENT_HASH = absentHash();
	private static final int TIP_HEIGHT = HEIGHTS - 1;
	private static final String ADDRESSES = "/bootstrap/addresses";
	private static final int MAXIMUM_ADDRESSES = LegacyWhiteList.SporkData.MAX_ENTRIES;
	private static final GenericType<List<AddressBalance>> BALANCE_LIST = new GenericType<>() { };

	@Inject
	private SporkDatabase sporkDatabase;

	/* The mint sporks of these tests are signed by no one, and stand for sporks that count */
	@BeforeTry
	public void forgetMints() {
		sporkDatabase.setMintStorage(null);

		new MockUp<GridSpork>() {
			@Mock public boolean isCounting() {
				return true;
			}
		};
	}

	private void mint(byte[] addressHash, int height, String amount) {
		if (Objects.isNull(sporkDatabase.getMintStorage())) {
			sporkDatabase.setMintStorage(new MintStorage());
		}

		sporkDatabase.getMintStorage().<MintStorage.SporkData>getData().getMints().put(
			new Location(new Address(LegacyAddress.encode(addressHash)), height), new BigDecimal(amount)
		);
	}

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
	public void shouldReportTheTipAsTheCurrentHeight() {
		writeSnapshot();
		assertThat(client.getEntity("/height", HeightResponse.class).height(), equalTo(TIP_HEIGHT));
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
	public void shouldAddTheMintsTheChainHasNotReached() {
		writeSnapshot();
		mint(ADDRESS_HASH, TIP_HEIGHT, "1");
		mint(ADDRESS_HASH, TIP_HEIGHT + 1, "2.5");
		mint(ABSENT_HASH, TIP_HEIGHT + 1, "9");

		final AddressBalance balance = client.getEntity("/bootstrap/address/"
			+ LegacyAddress.encode(ADDRESS_HASH), AddressBalance.class);

		assertThat(balance.getBalance(), equalTo(Coin.toDecimal(AMOUNT * HEIGHTS).add(new BigDecimal("2.5"))));
		assertThat(balance.getTransactionCount(), equalTo(HEIGHTS));
	}

	@Example
	@SneakyThrows
	public void shouldReportThePendingMintsOfAnAddressOutsideTheSnapshot() {
		writeSnapshot();
		mint(ABSENT_HASH, TIP_HEIGHT + 1, "3");

		final AddressBalance balance = client.getEntity("/bootstrap/address/"
			+ LegacyAddress.encode(ABSENT_HASH), AddressBalance.class);

		assertThat(balance.getBalance(), equalTo(new BigDecimal("3.00000000")));
		assertThat(balance.getTransactionCount(), equalTo(0));
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
		assertThat(page.get(0).getAmount(), equalTo(Coin.toDecimal(AMOUNT)));
		assertThat(page.get(0).getKind(), equalTo(EntryKind.RECEIVED));
		assertThat(page.get(0).getTransaction(), equalTo(BlockParser.toDisplayString(identifierAt(1))));
	}

	/* The snapshot of these tests holds one address, so it is both the first and the last holder */
	@Example
	@SneakyThrows
	public void shouldReadTheHolderAtAnIndexWithItsPlainSnapshotBalance() {
		writeSnapshot();
		mint(ADDRESS_HASH, TIP_HEIGHT + 1, "2.5");

		final Holder holder = client.getEntity("/bootstrap/holders/0", Holder.class);

		assertThat(holder.index(), equalTo(0));
		assertThat(holder.address(), equalTo(LegacyAddress.encode(ADDRESS_HASH)));
		assertThat(holder.balance(), equalTo(Coin.toDecimal(AMOUNT * HEIGHTS)));
		assertThat(holder.transactionCount(), equalTo(HEIGHTS));
	}

	@Example
	@SneakyThrows
	public void shouldNotFindAHolderPastTheLastAddress() {
		writeSnapshot();

		final Response response = client.get("/bootstrap/holders/1");

		assertThat(response.getStatus(), equalTo(Response.Status.NOT_FOUND.getStatusCode()));
	}

	/* A negative index or offset is a malformed request, which bean validation refuses before the snapshot is read */
	@Example
	@SneakyThrows
	public void shouldRejectANegativeIndexOrOffset() {
		writeSnapshot();

		for (final String location : List.of("/bootstrap/holders/-1", "/bootstrap/holders?offset=-1")) {
			try {
				client.get(location);
				throw new AssertionError(location + " was accepted");

			} catch (ResponseOddityException expected) {
				assertThat(location, expected.getMessage(), startsWith(
					String.valueOf(Response.Status.BAD_REQUEST.getStatusCode())));
			}
		}
	}

	@Example
	@SneakyThrows
	public void shouldPageThroughTheHoldersWithinTheLargestPage() {
		writeSnapshot();

		final GenericType<List<Holder>> holders = new GenericType<>() { };
		final List<Holder> first = client.get("/bootstrap/holders?offset=0&limit=5000").readEntity(holders);
		final List<Holder> past = client.get("/bootstrap/holders?offset=1").readEntity(holders);

		assertThat(first.size(), equalTo(1));
		assertThat(first.get(0).address(), equalTo(LegacyAddress.encode(ADDRESS_HASH)));
		assertThat(past, equalTo(List.of()));
	}

	/**
	 * A list is answered in one request, each address as it is answered alone: the one in the snapshot with its
	 * balance, the one outside it with the mint it is owed, and one the node knows nothing of left out.
	 */
	@Example
	@SneakyThrows
	public void shouldLookUpManyAddressesInOneRequest() {
		final byte[] unknownHash = absentHash();

		unknownHash[1] = 0x17;
		writeSnapshot();
		mint(ABSENT_HASH, TIP_HEIGHT + 1, "3");

		final String known = LegacyAddress.encode(ADDRESS_HASH);
		final String owed = LegacyAddress.encode(ABSENT_HASH);
		final List<AddressBalance> found = client.post(ADDRESSES,
			Entity.json(List.of(known, owed, LegacyAddress.encode(unknownHash)))).readEntity(BALANCE_LIST);

		assertThat(found.stream().map(AddressBalance::getAddress).toList(), equalTo(List.of(known, owed)));
		assertThat(found.get(0).getBalance(), equalTo(client.getEntity("/bootstrap/address/" + known,
			AddressBalance.class).getBalance()));
		assertThat(found.get(1).getBalance(), equalTo(new BigDecimal("3.00000000")));
		assertThat(found.get(1).getTransactionCount(), equalTo(0));
	}

	/** A malformed address, or more addresses than the cap, makes the whole list a malformed request. */
	@Example
	@SneakyThrows
	public void shouldRejectAMalformedAddressOrTooLongAList() {
		writeSnapshot();

		final List<String> tooMany = Collections.nCopies(MAXIMUM_ADDRESSES + 1, LegacyAddress.encode(ADDRESS_HASH));

		for (final List<String> addresses : List.of(List.of("NotAnAddress"), tooMany)) {
			try {
				client.post(ADDRESSES, Entity.json(addresses));
				throw new AssertionError(addresses.size() + " addresses were accepted");

			} catch (ResponseOddityException expected) {
				assertThat(expected.getMessage(), startsWith(
					String.valueOf(Response.Status.BAD_REQUEST.getStatusCode())));
			}
		}
	}

	/** A missing address is a malformed request too, not a failure of the node. */
	@Example
	@SneakyThrows
	public void shouldRejectAListWithAMissingAddress() {
		writeSnapshot();

		final List<String> withANull = Arrays.asList(LegacyAddress.encode(ADDRESS_HASH), null);

		try {
			client.post(ADDRESSES, Entity.json(withANull));
			throw new AssertionError("A list with a missing address was accepted");

		} catch (ResponseOddityException expected) {
			assertThat(expected.getMessage(), startsWith(
				String.valueOf(Response.Status.BAD_REQUEST.getStatusCode())));
		}
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
			entries.add(address, AMOUNT, height, transactionIds.add(identifierAt(height)), EntryKind.RECEIVED);
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

	private static byte[] identifierAt(int height) {
		final byte[] identifier = new byte[Hashing.HASH_SIZE];

		identifier[0] = (byte) height;
		return identifier;
	}

	private static byte[] absentHash() {
		final byte[] hash = new byte[Hashing.ADDRESS_HASH_SIZE];

		hash[0] = 0x42;
		return hash;
	}
}
