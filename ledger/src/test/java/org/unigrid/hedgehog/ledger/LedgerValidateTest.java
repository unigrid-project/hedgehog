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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

/* Validating a certified block is for blocks that the validators signed: it asks no policy, so a node that would
   not have voted for the block still agrees that it is right, and what it answers is what commit does */
public class LedgerValidateTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);
	private static final LegacyWallet HELD = new LegacyWallet(1, true);
	private static final Map<LegacyAddress, Long> SNAPSHOT = Map.of(HELD.address(), 500L);
	private static final Mint DUE = mint(1, 700);
	private static final long CLOCK_FAR_BEHIND = 1;

	private static Ledger open(FileSystem fileSystem, LedgerState state) throws IOException {
		return new Ledger(state, BlockLog.open(fileSystem.getPath("/data/blocks.log")), () -> CLOCK_FAR_BEHIND);
	}

	@Example
	public void shouldAcceptACertifiedBlockWithABlacklistedClaim() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final LedgerState lenient = stateWithSnapshot(ANY_MINT, FOUNDATION, 500, SNAPSHOT);
			final Block block = block(lenient, FOUNDATION, List.of(HELD.claim(account(1), lenient.chainId())));
			final Ledger strict = open(fileSystem,
				stateWithSnapshot(blacklisting(Set.of(HELD.address())), FOUNDATION, 500, SNAPSHOT));

			assertThat(strict.validateCertified(block), equalTo(Optional.empty()));

			strict.commit(block);

			assertThat(strict.balanceOf(account(1)), equalTo(500L));
		}
	}

	@Example
	public void shouldAcceptACertifiedBlockWithATransferBelowTheMinimumFee() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final LedgerState lenient = state(1000, ANY_MINT, FOUNDATION);
			final Ledger strict = open(fileSystem, state(1000, minimumFee(5), FOUNDATION));
			final Block funding = block(lenient, FOUNDATION, List.of(mint(1, 1000)));

			strict.commit(funding);
			lenient.apply(funding);

			final Block block = block(lenient, FOUNDATION, List.of(transfer(lenient, 1, account(2), 10, 1, 0)));

			assertThat(strict.validateCertified(block), equalTo(Optional.empty()));

			strict.commit(block);

			assertThat(strict.balanceOf(account(2)), equalTo(10L));
		}
	}

	@Example
	public void shouldAcceptACertifiedBlockWithAMintDatedBeforeItIsDue() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final LedgerState lenient = state(1000, ANY_MINT, FOUNDATION);
			final Block block = block(lenient, FOUNDATION, FOUNDATION.size(), 100_000, List.of(DUE));
			final Ledger strict = open(fileSystem, state(1000, dueAt(Map.of(DUE, 200_000L)), FOUNDATION));

			assertThat(strict.validateCertified(block), equalTo(Optional.empty()));

			strict.commit(block);

			assertThat(strict.balanceOf(account(1)), equalTo(700L));
		}
	}

	@Example
	public void shouldRefuseWhatCommitRefuses() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final LedgerState lenient = state(1000, ANY_MINT, FOUNDATION);
			final Block block = block(lenient, FOUNDATION, List.of(mint(1, 5)));
			final Ledger ledger = open(fileSystem, state(1000, ANY_MINT, FOUNDATION));

			ledger.commit(block);

			final String reason = ledger.validateCertified(block).orElseThrow();

			assertThat(assertThrows(InvalidBlockException.class, () -> ledger.commit(block)).getMessage(),
				equalTo(reason));
		}
	}
}
