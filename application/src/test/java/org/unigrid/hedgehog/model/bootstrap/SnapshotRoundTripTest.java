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

package org.unigrid.hedgehog.model.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

public class SnapshotRoundTripTest {
	private static final int HEIGHTS = 8;

	@Property(tries = 50)
	@SneakyThrows
	public void shouldReadBackEveryBalanceThatWasWritten(
		@ForAll @Size(min = 1, max = 12) List<@Size(Hashing.ADDRESS_HASH_SIZE) byte[]> hashes,
		@ForAll @LongRange(min = 1, max = 100_000_000_000L) long amount) {

		final Fixture fixture = write(hashes, amount);

		for (final byte[] hash : fixture.hashes) {
			final String address = LegacyAddress.encode(hash);

			assertThat(fixture.reader.balanceOf(address).orElseThrow().getBalance(),
				equalTo(Coin.toDecimal(amount)));
		}
	}

	@Property(tries = 50)
	@SneakyThrows
	public void shouldReadBackEveryHistoryInHeightOrder(
		@ForAll @Size(min = 1, max = 12) List<@Size(Hashing.ADDRESS_HASH_SIZE) byte[]> hashes,
		@ForAll @IntRange(min = 1, max = HEIGHTS) int entriesPerAddress) {

		final Fixture fixture = write(hashes, 1, entriesPerAddress);

		for (final byte[] hash : fixture.hashes) {
			final List<AddressTransaction> history = fixture.reader
				.transactionsOf(LegacyAddress.encode(hash), 0, Integer.MAX_VALUE);

			assertThat(history.size(), equalTo(entriesPerAddress));

			for (int i = 0; i < history.size(); i++) {
				assertThat(history.get(i).getHeight(), equalTo(i));
				assertThat(history.get(i).getTime(),
					equalTo(Instant.ofEpochSecond(SyntheticBlocks.timeAt(i))));
			}
		}
	}

	@Example
	@SneakyThrows
	public void shouldNotFindAnAddressThatWasNeverWritten() {
		final Fixture fixture = write(List.of(new byte[Hashing.ADDRESS_HASH_SIZE]), 1);
		final byte[] absent = new byte[Hashing.ADDRESS_HASH_SIZE];

		absent[0] = 0x42;
		assertThat(fixture.reader.balanceOf(LegacyAddress.encode(absent)).isEmpty(), equalTo(true));
		assertThat(fixture.reader.transactionsOf(LegacyAddress.encode(absent), 0, 10), empty());
	}

	@Example
	@SneakyThrows
	public void shouldPageThroughAHistory() {
		final Fixture fixture = write(List.of(new byte[Hashing.ADDRESS_HASH_SIZE]), 1, HEIGHTS);
		final String address = LegacyAddress.encode(new byte[Hashing.ADDRESS_HASH_SIZE]);

		assertThat(heightsOf(fixture.reader.transactionsOf(address, 0, 3)), contains(0, 1, 2));
		assertThat(heightsOf(fixture.reader.transactionsOf(address, 3, 3)), contains(3, 4, 5));
		assertThat(heightsOf(fixture.reader.transactionsOf(address, HEIGHTS, 3)), empty());
	}

	@Example
	@SneakyThrows
	public void shouldReadBackDistinctValuesAgainstTheRightAddress() {
		final byte[] first = addressHash((byte) 0x01);
		final byte[] second = addressHash((byte) 0x02);
		final byte[] third = addressHash((byte) 0x03);

		/* Registered in reverse hash order on purpose, so the address table's sort is a real
		   permutation and a writer that confused a rank with an address id would be caught. */
		final AddressRegistry addresses = new AddressRegistry();
		final int thirdId = addresses.idOf(third);
		final int secondId = addresses.idOf(second);
		final int firstId = addresses.idOf(first);

		final LedgerEntries entries = new LedgerEntries(16);
		final TransactionIdTable transactionIds = new TransactionIdTable();

		addEntry(entries, transactionIds, firstId, 0, 10, EntryKind.MINED);
		addEntry(entries, transactionIds, secondId, 0, 20, EntryKind.RECEIVED);
		addEntry(entries, transactionIds, secondId, 1, -5, EntryKind.SENT);
		addEntry(entries, transactionIds, thirdId, 0, 7, EntryKind.STAKED);
		addEntry(entries, transactionIds, thirdId, 1, 3, EntryKind.RECEIVED);
		addEntry(entries, transactionIds, thirdId, 2, 2, EntryKind.RECEIVED);

		final Path path = Files.createTempFile("hhg-snapshot-", ".dat");
		final long[] balances = new long[3];

		balances[firstId] = 10;
		balances[secondId] = 15;
		balances[thirdId] = 12;
		path.toFile().deleteOnExit();
		SnapshotWriter.write(path, chain(), Ledger.builder().addresses(addresses).entries(entries)
			.transactionIds(transactionIds).balances(balances).totalUnspent(37).build());

		final SnapshotReader reader = SnapshotReader.open(path);

		assertAddress(reader, first, firstId, 10, List.of(entry(0, 10, EntryKind.MINED)));
		assertAddress(reader, second, secondId, 15,
			List.of(entry(0, 20, EntryKind.RECEIVED), entry(1, -5, EntryKind.SENT)));
		assertAddress(reader, third, thirdId, 12, List.of(entry(0, 7, EntryKind.STAKED),
			entry(1, 3, EntryKind.RECEIVED), entry(2, 2, EntryKind.RECEIVED)));
	}

	@Example
	@SneakyThrows
	public void shouldClampANegativeOffsetToTheStartOfHistoryInsteadOfLeakingThePrecedingAddress() {
		final byte[] first = new byte[Hashing.ADDRESS_HASH_SIZE];
		final byte[] second = new byte[Hashing.ADDRESS_HASH_SIZE];

		second[0] = 0x01;

		final Fixture fixture = write(List.of(first, second), 1, HEIGHTS);
		final String secondAddress = LegacyAddress.encode(second);

		assertThat(fixture.reader.transactionsOf(secondAddress, -3, HEIGHTS),
			equalTo(fixture.reader.transactionsOf(secondAddress, 0, HEIGHTS)));
	}

	@Example
	@SneakyThrows
	public void shouldNotThrowForANegativeOffsetOnTheAlphabeticallyFirstAddress() {
		final byte[] first = new byte[Hashing.ADDRESS_HASH_SIZE];
		final Fixture fixture = write(List.of(first), 1, HEIGHTS);
		final String address = LegacyAddress.encode(first);

		assertThat(fixture.reader.transactionsOf(address, -3, HEIGHTS),
			equalTo(fixture.reader.transactionsOf(address, 0, HEIGHTS)));
	}

	@Example
	@SneakyThrows
	public void shouldCarryTheChainSummaryInTheHeader() {
		final Fixture fixture = write(List.of(new byte[Hashing.ADDRESS_HASH_SIZE]), 7);
		final SnapshotInfo info = fixture.reader.getInfo();

		assertThat(info.getTipHeight(), equalTo(HEIGHTS - 1));
		assertThat(info.getAddressCount(), equalTo(1));
		assertThat(info.getEntryCount(), equalTo(1L));
		assertThat(info.getTotalUnspent(), equalTo(Coin.toDecimal(7)));
	}

	private static List<Integer> heightsOf(List<AddressTransaction> transactions) {
		final List<Integer> heights = new ArrayList<>();

		transactions.forEach(transaction -> heights.add(transaction.getHeight()));
		return heights;
	}

	private static Fixture write(List<byte[]> hashes, long amount) throws IOException {
		return write(hashes, amount, 1);
	}

	private static Fixture write(List<byte[]> hashes, long amount, int entriesPerAddress)
		throws IOException {

		final AddressRegistry addresses = new AddressRegistry();
		final LedgerEntries entries = new LedgerEntries(16);
		final TransactionIdTable transactionIds = new TransactionIdTable();
		final List<byte[]> distinct = new ArrayList<>();

		for (final byte[] hash : hashes) {
			final int address = addresses.idOf(hash);

			if (address == distinct.size()) {
				distinct.add(hash);

				for (int height = 0; height < entriesPerAddress; height++) {
					entries.add(address, amount, height,
						transactionIds.add(identifier(address, height)), EntryKind.RECEIVED);
				}
			}
		}

		final long[] balances = new long[addresses.size()];

		for (int address = 0; address < balances.length; address++) {
			balances[address] = amount * entriesPerAddress;
		}

		final Path path = Files.createTempFile("hhg-snapshot-", ".dat");

		path.toFile().deleteOnExit();
		SnapshotWriter.write(path, chain(), Ledger.builder().addresses(addresses).entries(entries)
			.transactionIds(transactionIds).balances(balances)
			.totalUnspent(amount * entriesPerAddress * balances.length).build());

		return new Fixture(distinct, SnapshotReader.open(path));
	}

	private static byte[] addressHash(byte marker) {
		final byte[] hash = new byte[Hashing.ADDRESS_HASH_SIZE];

		hash[0] = marker;
		return hash;
	}

	private static void addEntry(LedgerEntries entries, TransactionIdTable transactionIds,
		int address, int height, long amount, EntryKind kind) {

		entries.add(address, amount, height, transactionIds.add(identifier(address, height)), kind);
	}

	private static ExpectedEntry entry(int height, long amount, EntryKind kind) {
		return new ExpectedEntry(height, amount, kind);
	}

	private static void assertAddress(SnapshotReader reader, byte[] hash, int addressId,
		long balance, List<ExpectedEntry> expected) {

		final String address = LegacyAddress.encode(hash);
		final List<AddressTransaction> history = reader.transactionsOf(address, 0, expected.size());

		assertThat(reader.balanceOf(address).orElseThrow().getBalance(), equalTo(Coin.toDecimal(balance)));
		assertThat(reader.balanceOf(address).orElseThrow().getTransactionCount(), equalTo(expected.size()));
		assertThat(history.size(), equalTo(expected.size()));

		for (int i = 0; i < expected.size(); i++) {
			assertExpectedEntry(history.get(i), expected.get(i), addressId);
		}
	}

	private static void assertExpectedEntry(AddressTransaction actual, ExpectedEntry expected, int addressId) {
		assertThat(actual.getHeight(), equalTo(expected.height()));
		assertThat(actual.getAmount(), equalTo(Coin.toDecimal(expected.amount())));
		assertThat(actual.getKind(), equalTo(expected.kind()));
		assertThat(actual.getTransaction(),
			equalTo(BlockParser.toDisplayString(identifier(addressId, expected.height()))));
	}

	private record ExpectedEntry(int height, long amount, EntryKind kind) {
	}

	private static byte[] identifier(int address, int height) {
		final byte[] identifier = new byte[Hashing.HASH_SIZE];

		identifier[0] = (byte) address;
		identifier[1] = (byte) height;
		return identifier;
	}

	private static Chain chain() {
		final int[] empty = new int[HEIGHTS];
		final int[] times = new int[HEIGHTS];

		for (int height = 0; height < HEIGHTS; height++) {
			times[height] = SyntheticBlocks.timeAt(height);
		}

		return new Chain(empty, empty, empty, times, new byte[Hashing.HASH_SIZE], HEIGHTS);
	}

	private static final class Fixture {
		private final List<byte[]> hashes;
		private final SnapshotReader reader;

		private Fixture(List<byte[]> hashes, SnapshotReader reader) {
			this.hashes = hashes;
			this.reader = reader;
		}
	}
}
