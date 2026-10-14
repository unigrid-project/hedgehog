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

import java.util.List;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class MempoolTransferTest {
	private static LedgerState funded() {
		final LedgerState state = soloState(ANY_MINT);

		state.apply(soloBlock(state, mint(1, 1000), mint(2, 1000)));
		return state;
	}

	private static Transfer send(LedgerState state, int from, long amount, long fee, long nonce) {
		return transfer(state, from, account(9), amount, fee, nonce);
	}

	@Example
	public void shouldQueueTransfersAheadOfTheNextNonceWithinTheWindowOnly() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(send(state, 1, 1, 1, 0), state), is(true));
		assertThat(mempool.admit(send(state, 1, 1, 1, 1), state), is(true));
		assertThat(mempool.admit(send(state, 1, 1, 1, Mempool.NONCE_WINDOW - 1), state), is(true));
		assertThat(mempool.admit(send(state, 1, 1, 1, Mempool.NONCE_WINDOW), state), is(false));
		assertThat(mempool.size(), equalTo(3));
	}

	@Example
	public void shouldHoldNoMoreThanOneWindowPerSenderAndLeaveOtherSendersAlone() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();

		for (int nonce = 0; nonce < Mempool.NONCE_WINDOW; nonce++) {
			assertThat(mempool.admit(send(state, 1, 1, 1, nonce), state), is(true));
		}

		assertThat(mempool.admit(send(state, 1, 1, 1, Mempool.NONCE_WINDOW), state), is(false));
		assertThat(mempool.admit(send(state, 2, 1, 1, 0), state), is(true));
		assertThat(mempool.size(), equalTo(Mempool.NONCE_WINDOW + 1));
	}

	@Example
	public void shouldRefuseANonceThatIsAlreadyUsed() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();

		state.apply(soloBlock(state, send(state, 1, 1, 1, 0)));

		assertThat(mempool.admit(send(state, 1, 2, 1, 0), state), is(false));
		assertThat(mempool.admit(send(state, 1, 2, 1, 1), state), is(true));
	}

	@Example
	public void shouldRefuseWhatTheBalanceDoesNotCoverTogetherWithWhatAlreadyWaits() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(send(state, 1, 590, 10, 0), state), is(true));
		assertThat(mempool.admit(send(state, 1, 490, 10, 1), state), is(false));
		assertThat(mempool.admit(send(state, 1, 390, 10, 1), state), is(true));
	}

	@Example
	public void shouldReplaceATransferOnlyForAHigherFee() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(send(state, 1, 100, 5, 0), state), is(true));
		assertThat(mempool.admit(send(state, 1, 100, 5, 0), state), is(false));
		assertThat(mempool.admit(send(state, 1, 200, 4, 0), state), is(false));
		assertThat(mempool.admit(send(state, 1, 200, 6, 0), state), is(true));
		assertThat(mempool.size(), equalTo(1));
		assertThat(((Transfer) mempool.take(10).get(0)).fee(), equalTo(6L));
	}

	@Example
	public void shouldNotCountTheTransferItReplacesAgainstTheBalance() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(send(state, 1, 900, 5, 0), state), is(true));
		assertThat(mempool.admit(send(state, 1, 950, 10, 0), state), is(true));
	}

	@Example
	public void shouldHandOutATransfersInNonceOrderWhateverTheArrivalOrder() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();
		final Transfer two = send(state, 1, 1, 1, 2);
		final Transfer zero = send(state, 1, 1, 1, 0);
		final Transfer one = send(state, 1, 1, 1, 1);
		final Mint mint = mint(5, 7);

		mempool.admit(two, state);
		mempool.admit(mint, state);
		mempool.admit(zero, state);
		mempool.admit(one, state);

		final List<Transaction> taken = mempool.take(10);

		assertThat(taken, equalTo(List.of(zero, mint, one, two)));
		assertThat(state.applicable(taken).size(), equalTo(4));
		assertThat(mempool.take(2), equalTo(List.of(zero, mint)));
	}

	@Example
	public void shouldKeepTheLaterTransfersWhenABlockTakesTheEarlierOnes() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();
		final Transfer zero = send(state, 1, 1, 1, 0);

		mempool.admit(zero, state);
		mempool.admit(send(state, 1, 1, 1, 1), state);
		mempool.admit(send(state, 1, 1, 1, 2), state);

		final Block block = soloBlock(state, zero);

		state.apply(block);
		mempool.removeCommitted(block, state);

		assertThat(mempool.size(), equalTo(2));
	}

	@Example
	public void shouldDropAQueuedTransferThatTheBalanceNoLongerCovers() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(send(state, 1, 900, 5, 1), state), is(true));

		final Block block = soloBlock(state, send(state, 1, 950, 5, 0));

		state.apply(block);
		mempool.removeCommitted(block, state);

		assertThat(mempool.size(), equalTo(0));
	}
}
