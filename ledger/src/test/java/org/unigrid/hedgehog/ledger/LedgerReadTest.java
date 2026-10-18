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

package org.unigrid.hedgehog.ledger;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerReadTest {
	private static final List<byte[]> SOLO = seeds(1, 1);
	private static final LegacyWallet WALLET = new LegacyWallet(1, true);
	private static final LegacyAddress LEGACY = WALLET.address();
	private static final long OWED = 500;
	private static final String LOG = "/data/blocks.log";

	private static Ledger open(FileSystem fileSystem) throws IOException {
		return new Ledger(soloSnapshotState(ANY_MINT, Map.of(LEGACY, OWED)), BlockLog.open(fileSystem.getPath(LOG)));
	}

	private static void seal(Ledger ledger, Transaction... transactions) throws IOException {
		for (final Transaction transaction : transactions) {
			assertThat(ledger.submit(transaction), equalTo(Optional.empty()));
		}

		ledger.commit(nextBlock(ledger, SOLO, 2000 + ledger.height()));
	}

	@Example
	public void shouldGiveTheNextNonceOfAnAccountThatHasMadeTransfers() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);

			assertThat(ledger.nonceOf(account(1)), equalTo(0L));

			seal(ledger, mint(1, 1000));
			seal(ledger, paying(ledger, 100, 1, 0));

			assertThat(ledger.nonceOf(account(1)), equalTo(1L));
			assertThat(ledger.nonceOf(account(2)), equalTo(0L));
		}
	}

	@Example
	public void shouldNameTheAccountAClaimMovedAnAddressOntoAlsoAfterReplay() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);

			assertThat(ledger.claimedBy(LEGACY), equalTo(Optional.empty()));

			seal(ledger, WALLET.claim(account(1), ledger.chainId()));

			assertThat(ledger.claimedBy(LEGACY), equalTo(Optional.of(account(1))));
			assertThat(ledger.claimedBy(new LegacyWallet(2, true).address()), equalTo(Optional.empty()));
			assertThat(open(fileSystem).claimedBy(LEGACY), equalTo(Optional.of(account(1))));
		}
	}

	@Example
	public void shouldCountWhatWasMintedToTheAddressAsClaimableAndNothingOnceClaimed() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);

			assertThat(ledger.claimable(LEGACY), equalTo(OWED));
			assertThat(ledger.claimable(new LegacyWallet(2, true).address()), equalTo(0L));

			seal(ledger, new Mint(LEGACY, 30, reference(1)));

			assertThat(ledger.claimable(LEGACY), equalTo(OWED + 30));

			seal(ledger, WALLET.claim(account(1), ledger.chainId()));
			seal(ledger, new Mint(LEGACY, 20, reference(2)));

			assertThat(ledger.claimable(LEGACY), equalTo(0L));
		}
	}

	@Property(tries = 20)
	public void shouldBeExactlyWhatTheClaimCredits(@ForAll @LongRange(min = 0, max = 1_000_000) long minted)
		throws IOException {

		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);

			if (minted > 0) {
				seal(ledger, new Mint(LEGACY, minted, reference(1)));
			}

			final long claimable = ledger.claimable(LEGACY);

			seal(ledger, WALLET.claim(account(1), ledger.chainId()));

			assertThat(claimable, equalTo(OWED + minted));
			assertThat(ledger.balanceOf(account(1)), equalTo(claimable));
		}
	}

	@Example
	public void shouldGiveTheBlockOfAHeightAndNothingOutsideTheChain() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);

			seal(ledger, mint(1, 1000));
			seal(ledger, mint(2, 1000));

			assertThat(ledger.block(1), equalTo(Optional.of(ledger.blocksFrom(1, 1).get(0))));
			assertThat(ledger.block(1).orElseThrow().getHeight(), equalTo(1L));
			assertThat(ledger.block(2).orElseThrow().getHeight(), equalTo(ledger.height()));

			for (final long outside : new long[] { 0, -1, Long.MIN_VALUE, ledger.height() + 1, Long.MAX_VALUE }) {
				assertThat("height " + outside, ledger.block(outside).isPresent(), is(false));
			}
		}
	}
}
