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

package org.unigrid.hedgehog.service.ledger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.unigrid.hedgehog.ledger.LegacyAddress;
import org.unigrid.hedgehog.ledger.MintSchedule;
import org.unigrid.hedgehog.model.bootstrap.AddressBalance;
import org.unigrid.hedgehog.model.bootstrap.AddressSnapshots;
import org.unigrid.hedgehog.model.bootstrap.BootstrapSnapshot;
import org.unigrid.hedgehog.model.bootstrap.Coin;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;

public class LedgerSnapshotTest {
	private static final long BLOCK_MILLIS = 60_000;

	@Provide
	public Arbitrary<Map<String, Long>> provideHoldings() {
		return AddressSnapshots.holdings(Long.MAX_VALUE);
	}

	@Provide
	public Arbitrary<List<String>> provideProbes() {
		return AddressSnapshots.hashes().list().ofMaxSize(20);
	}

	@Property(tries = 50)
	@SneakyThrows
	public void shouldAgreeWithTheDecimalBalanceOnAnyAddress(@ForAll("provideHoldings") Map<String, Long> holdings,
		@ForAll("provideProbes") List<String> probes) {

		final SnapshotReader reader = SnapshotReader.open(AddressSnapshots.write(holdings));
		final LedgerSnapshot snapshot = new LedgerSnapshot(reader);
		final List<String> hashes = new ArrayList<>(holdings.keySet());

		hashes.addAll(probes);

		for (final String hash : hashes) {
			assertThat(snapshot.balanceOf(new LegacyAddress(AddressSnapshots.bytesOf(hash))), equalTo(
				reader.balanceOf(AddressSnapshots.addressOf(hash)).map(AddressBalance::getBalance)
					.map(balance -> balance.movePointRight(Coin.DECIMALS).longValueExact()).orElse(0L)
			));
		}
	}

	@Property(tries = 30)
	@SneakyThrows
	public void shouldScheduleMintsFromTheTipOfTheSnapshot(@ForAll @IntRange(min = 0, max = 60) int height,
		@ForAll @LongRange(min = 0, max = 0xFFFFFFFFL) long seconds) {

		final LedgerSnapshot snapshot = new LedgerSnapshot(SnapshotReader.open(
			AddressSnapshots.write(Map.of(), height, (int) seconds)));

		assertThat(snapshot.mintSchedule(), equalTo(new MintSchedule(height, seconds * 1000)));
		assertThat(snapshot.mintSchedule().dueTime(height + 1L), equalTo(seconds * 1000 + BLOCK_MILLIS));
		assertThrows(IllegalArgumentException.class, () -> snapshot.mintSchedule().dueTime(height));
	}

	@Property(tries = 20)
	@SneakyThrows
	public void shouldAnswerConcurrentReadsLikeSequentialOnes(@ForAll("provideHoldings") Map<String, Long> holdings) {
		final LedgerSnapshot snapshot = new LedgerSnapshot(SnapshotReader.open(AddressSnapshots.write(holdings)));
		final List<String> hashes = List.copyOf(holdings.keySet());

		if (!hashes.isEmpty()) {
			assertThat(IntStream.range(0, 4000).parallel().allMatch(i -> {
				final String hash = hashes.get(i % hashes.size());
				final LegacyAddress address = new LegacyAddress(AddressSnapshots.bytesOf(hash));

				return snapshot.balanceOf(address) == holdings.get(hash);
			}), equalTo(true));
		}
	}

	@Example
	@SneakyThrows
	public void shouldKeepAnsweringFromTheSnapshotItWasBuiltOn() {
		final String hash = "11".repeat(LegacyAddress.SIZE);
		final Path file = AddressSnapshots.write(Map.of(hash, 5L));
		final BootstrapSnapshot bootstrap = new BootstrapSnapshot(file);
		final LedgerSnapshot snapshot = new LedgerSnapshot(bootstrap.getReader().orElseThrow());
		final LegacyAddress address = new LegacyAddress(AddressSnapshots.bytesOf(hash));

		Files.move(AddressSnapshots.write(Map.of(hash, 7L, "22".repeat(LegacyAddress.SIZE), 9L)), file,
			StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

		assertThat(bootstrap.getReader().orElseThrow().balanceUnits(address.bytes()), equalTo(7L));
		assertThat(snapshot.balanceOf(address), equalTo(5L));
	}
}
