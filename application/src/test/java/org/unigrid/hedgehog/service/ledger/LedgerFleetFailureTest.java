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

import java.time.Duration;
import java.util.Optional;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;

/* What a network has to survive once it is live: a validator that is down when its turn comes, and too few of them to
   make a block. Every example stops what it started on, so the next one finds all four validators running. */
public class LedgerFleetFailureTest extends BaseLedgerFleetTest {
	private static final int VALIDATORS = 4;

	private static AccountKey account(final int n) {
		return Ed25519.publicKey(LedgerTestChain.seed(600 + n));
	}

	/* The validator whose turn the next block is, in the first view */
	private int proposerOfTheNextBlock() {
		final LedgerFleet fleet = fleet();
		final AccountKey proposer = fleet.genesis().getValidators().proposerAt(fleet.height(0) + 1);

		return IntStream.range(0, VALIDATORS).filter(node -> Ed25519.publicKey(fleet.seed(node)).equals(proposer))
			.findFirst().orElseThrow();
	}

	private void allowClaims() {
		fleet().publishSpork(Type.LEDGER_BLACKLIST, new LedgerBlacklist.SporkData());
	}

	private Optional<String> claim(final int via, final int wallet, final AccountKey onto) {
		final LedgerFleet fleet = fleet();

		return fleet.submit(via, fleet.wallet(wallet).claim(onto, fleet.chainId()));
	}

	@Example
	public void shouldGoOnWithTheNextViewWhenTheProposerOfTheNextBlockIsDown() {
		final LedgerFleet fleet = fleet();
		final AccountKey owner = account(1);

		allowClaims();

		final int proposer = proposerOfTheNextBlock();
		final int via = (proposer + 1) % VALIDATORS;
		final long before = fleet.height(via);

		fleet.stop(proposer);
		assertThat(claim(via, 0, owner), equalTo(Optional.empty()));

		IntStream.range(0, fleet.size()).filter(node -> node != proposer)
			.forEach(node -> fleet.awaitHeight(node, before + 1));
		assertThat(fleet.balanceOf(via, owner), is(fleet.holding(0)));

		fleet.restart(proposer);
		fleet.awaitHeight(proposer, before + 1);
		fleet.awaitSameChain();
		assertThat(fleet.balanceOf(proposer, owner), is(fleet.holding(0)));
	}

	@Example
	public void shouldMakeNoBlockWithoutAQuorumAndKeepTheTransactionUntilThereIsOne() {
		final LedgerFleet fleet = fleet();
		final AccountKey owner = account(2);

		allowClaims();

		final long before = fleet.height(0);

		fleet.stop(2);
		fleet.stop(3);
		assertThat(claim(0, 1, owner), equalTo(Optional.empty()));

		await().during(Duration.ofSeconds(8)).until(() -> fleet.height(0) == before && fleet.height(1) == before);
		assertThat(fleet.balanceOf(0, owner), is(0L));

		fleet.restart(2);
		fleet.awaitHeight(0, before + 1);
		fleet.awaitHeight(1, before + 1);
		assertThat(fleet.balanceOf(0, owner), is(fleet.holding(1)));

		fleet.restart(3);
		fleet.awaitSameChain();
		assertThat(fleet.balanceOf(3, owner), is(fleet.holding(1)));
	}
}
