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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.ledger.ValidatorSet;
import org.unigrid.hedgehog.model.bootstrap.AddressSnapshots;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDigest;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;

public class SnapshotCheckTest {
	private static final HexFormat HEX = HexFormat.of();
	private static final Map<String, Long> ONE_HOLDER = Map.of("11".repeat(20), 5L);

	private record Written(Path file, SnapshotReader reader, byte[] hash, long total) {
		@SneakyThrows
		static Written of(Map<String, Long> holdings) {
			final Path file = AddressSnapshots.write(holdings);
			final SnapshotReader reader = SnapshotReader.open(file);

			return new Written(file, reader, SnapshotDigest.of(file), reader.totalBalance());
		}

		Optional<String> verify(byte[] genesisHash, long genesisTotal) {
			return SnapshotCheck.verify(file, reader, genesis(genesisHash, genesisTotal));
		}
	}

	@Provide
	public Arbitrary<Map<String, Long>> provideHoldings() {
		return AddressSnapshots.holdings(AddressSnapshots.MAX_UNITS);
	}

	@Property(tries = 30)
	public void shouldAcceptAnUnsignedSnapshotThatMatchesTheGenesis(
		@ForAll("provideHoldings") Map<String, Long> holdings) {

		final Written snapshot = Written.of(holdings);

		assertThat(snapshot.verify(snapshot.hash(), snapshot.total()), equalTo(Optional.empty()));
	}

	@Property(tries = 50)
	public void shouldNameAFlippedHashAndPrintBothHashes(@ForAll("provideHoldings") Map<String, Long> holdings,
		@ForAll @IntRange(min = 0, max = 63) int index, @ForAll @IntRange(min = 0, max = 7) int bit) {

		final Written snapshot = Written.of(holdings);
		final byte[] flipped = snapshot.hash().clone();

		flipped[index] ^= (byte) (1 << bit);

		final String message = snapshot.verify(flipped, snapshot.total()).orElseThrow();

		assertThat(message, containsString(snapshot.file().toString()));
		assertThat(message, containsString("content hash " + HEX.formatHex(snapshot.hash())));
		assertThat(message, containsString("expects " + HEX.formatHex(flipped)));
		assertThat(message, not(containsString("adds up to")));
	}

	@Property(tries = 50)
	public void shouldNameAWrongTotalAndPrintBothTotals(@ForAll("provideHoldings") Map<String, Long> holdings,
		@ForAll @LongRange(min = -1_000_000, max = 1_000_000) long difference) {

		final Written snapshot = Written.of(holdings);

		Assume.that(difference != 0 && snapshot.total() + difference >= 0);

		final String message = snapshot.verify(snapshot.hash(), snapshot.total() + difference).orElseThrow();

		assertThat(message, containsString(snapshot.file().toString()));
		assertThat(message, containsString("adds up to " + snapshot.total() + " units"));
		assertThat(message, containsString("expects " + (snapshot.total() + difference)));
		assertThat(message, not(containsString("content hash")));
	}

	@Example
	public void shouldNameBothWhenTheHashAndTheTotalDiffer() {
		final Written snapshot = Written.of(ONE_HOLDER);
		final String message = snapshot.verify(new byte[Digests.HASH_SIZE], 6).orElseThrow();

		assertThat(message, containsString("content hash " + HEX.formatHex(snapshot.hash())));
		assertThat(message, containsString("expects " + "00".repeat(Digests.HASH_SIZE)));
		assertThat(message, containsString("adds up to 5 units"));
		assertThat(message, containsString("expects 6"));
	}

	@Example
	public void shouldSayWhenTheFileCannotBeRead() {
		final Written snapshot = Written.of(ONE_HOLDER);
		final Path missing = Jimfs.newFileSystem(Configuration.unix()).getPath("/snapshots/missing.dat");
		final String message = SnapshotCheck.verify(missing, snapshot.reader(),
			genesis(snapshot.hash(), snapshot.total())).orElseThrow();

		assertThat(message, containsString("Cannot read the snapshot " + missing));
		assertThat(message, not(containsString("adds up to")));
	}

	private static LedgerGenesis genesis(byte[] hash, long total) {
		return new LedgerGenesis(1_700_000_000_000L, 10,
			new ValidatorSet(List.of(AccountKey.fromHex("11".repeat(AccountKey.SIZE)))), hash, total);
	}
}
