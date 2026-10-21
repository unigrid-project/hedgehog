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
import java.util.stream.IntStream;
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

	private static LedgerState fundedBy(int senders) {
		final LedgerState state = soloState(ANY_MINT);
		final Transaction[] mints = IntStream.rangeClosed(1, senders).mapToObj(n -> mint(n, 1000))
			.toArray(Transaction[]::new);

		state.apply(soloBlock(state, mints));
		return state;
	}

	private static Transfer send(LedgerState state, int from, long amount, long fee, long nonce) {
		return transfer(state, from, account(9), amount, fee, nonce);
	}

	@Example
	public void shouldHandOutNoTransferBehindAGapInTheNonce() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();
		final Transfer zero = send(state, 1, 1, 1, 0);
		final Transfer one = send(state, 1, 1, 1, 1);

		mempool.admit(send(state, 1, 1, 1, 3), state);
		mempool.admit(one, state);
		assertThat(mempool.proposable(10, state), equalTo(List.of()));

		mempool.admit(zero, state);
		assertThat(mempool.proposable(10, state), equalTo(List.of(zero, one)));
		assertThat(mempool.peek(10).size(), equalTo(3));
	}

	@Example
	public void shouldLetAReadyTransferThroughAFloodOfGappedOnes() {
		final LedgerState state = fundedBy(6);
		final Mempool mempool = new Mempool();
		final Transfer ready = send(state, 6, 1, 1, 0);

		for (int sender = 1; sender <= 5; sender++) {
			for (int nonce = 1; nonce <= 3; nonce++) {
				mempool.admit(send(state, sender, 1, 1, nonce), state);
			}
		}

		mempool.admit(ready, state);
		assertThat(mempool.proposable(1, state), equalTo(List.of(ready)));
	}

	@Example
	public void shouldMakeRoomForAReadyTransferByDroppingTheNewestGappedOne() {
		final LedgerState state = fundedBy(3);
		final Mempool mempool = new Mempool(2);
		final Transfer gapped = send(state, 1, 1, 1, 1);
		final Transfer ready = send(state, 3, 1, 1, 0);

		mempool.admit(gapped, state);
		mempool.admit(send(state, 2, 1, 1, 1), state);

		assertThat(mempool.admit(ready, state), is(true));
		assertThat(mempool.peek(10), equalTo(List.of(gapped, ready)));
	}

	@Example
	public void shouldNotDropAReadyTransferForAGappedOneHoweverHighItsFee() {
		final LedgerState state = fundedBy(2);
		final Mempool mempool = new Mempool(1);
		final Transfer ready = send(state, 1, 1, 1, 0);

		mempool.admit(ready, state);

		assertThat(mempool.admit(send(state, 2, 1, 50, 1), state), is(false));
		assertThat(mempool.peek(10), equalTo(List.of(ready)));
	}

	@Example
	public void shouldDropTheReadyTransferOfTheLowestFeeForOneThatPaysMore() {
		final LedgerState state = fundedBy(4);
		final Mempool mempool = new Mempool(2);
		final Transfer dear = send(state, 2, 1, 5, 0);
		final Transfer better = send(state, 3, 1, 3, 0);

		mempool.admit(send(state, 1, 1, 1, 0), state);
		mempool.admit(dear, state);

		assertThat(mempool.admit(better, state), is(true));
		assertThat(mempool.admit(send(state, 4, 1, 3, 0), state), is(false));
		assertThat(mempool.peek(10), equalTo(List.of(dear, better)));
	}

	@Example
	public void shouldMakeRoomForOtherKindsOfTransactionByDroppingAGappedTransferOnly() {
		final LedgerState state = fundedBy(2);
		final Mempool gapped = new Mempool(1);
		final Mempool ready = new Mempool(1);
		final Mint mint = mint(5, 7);

		gapped.admit(send(state, 1, 1, 1, 1), state);
		assertThat(gapped.admit(mint, state), is(true));
		assertThat(gapped.peek(10), equalTo(List.of(mint)));

		ready.admit(send(state, 2, 1, 1, 0), state);
		assertThat(ready.admit(mint, state), is(false));
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
		assertThat(((Transfer) mempool.peek(10).get(0)).fee(), equalTo(6L));
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

		final List<Transaction> taken = mempool.peek(10);

		assertThat(taken, equalTo(List.of(zero, mint, one, two)));
		assertThat(state.applicable(taken).size(), equalTo(4));
		assertThat(mempool.peek(2), equalTo(List.of(zero, mint)));
	}

	@Example
	public void shouldHandOutEachSendersTransfersInItsOwnNonceOrderInTheirPlaces() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();
		final Transfer firstLater = send(state, 1, 1, 1, 1);
		final Transfer secondLater = send(state, 2, 1, 1, 1);
		final Transfer secondEarlier = send(state, 2, 1, 1, 0);
		final Transfer firstEarlier = send(state, 1, 1, 1, 0);

		mempool.admit(firstLater, state);
		mempool.admit(secondLater, state);
		mempool.admit(secondEarlier, state);
		mempool.admit(firstEarlier, state);

		final List<Transaction> taken = mempool.peek(10);

		assertThat(taken, equalTo(List.of(firstEarlier, secondEarlier, secondLater, firstLater)));
		assertThat(state.applicable(taken).size(), equalTo(4));
	}

	@Example
	public void shouldHandOutNothingForANegativeLimit() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();

		mempool.admit(send(state, 1, 1, 1, 0), state);

		assertThat(mempool.peek(-1), equalTo(List.of()));
	}

	@Example
	public void shouldReplaceATransferAtAFullPoolButQueueNoOtherOne() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool(1);

		assertThat(mempool.admit(send(state, 1, 1, 5, 0), state), is(true));
		assertThat(mempool.admit(send(state, 1, 1, 6, 0), state), is(true));
		assertThat(mempool.admit(send(state, 1, 1, 5, 1), state), is(false));
		assertThat(mempool.size(), equalTo(1));
		assertThat(((Transfer) mempool.peek(10).get(0)).fee(), equalTo(6L));
	}

	@Example
	public void shouldMoveTheWindowUpWithTheNonceOfTheSender() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();

		state.apply(soloBlock(state, send(state, 1, 1, 1, 0)));

		assertThat(mempool.admit(send(state, 1, 1, 1, Mempool.NONCE_WINDOW), state), is(true));
		assertThat(mempool.admit(send(state, 1, 1, 1, Mempool.NONCE_WINDOW + 1), state), is(false));
	}

	@Example
	public void shouldCountWhatWaitsWhenTheBalanceIsNearTheLargestLong() {
		final long half = Long.MAX_VALUE / 2 - 10;
		final LedgerState state = soloState(ANY_MINT);
		final Mempool mempool = new Mempool();

		state.apply(soloBlock(state, new Mint(account(1), half, reference(1)),
			new Mint(account(1), half, reference(2))));

		assertThat(mempool.admit(send(state, 1, half - 10, 1, 0), state), is(true));
		assertThat(mempool.admit(send(state, 1, half - 10, 1, 1), state), is(true));
		assertThat(mempool.admit(send(state, 1, 100, 1, 2), state), is(false));
		assertThat(mempool.admit(send(state, 1, 5, 1, 2), state), is(true));
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

	@Example
	public void shouldDropAQueuedTransferWhoseNonceAnotherTransferUsedUp() {
		final LedgerState state = funded();
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(send(state, 1, 1, 1, 1), state), is(true));

		final Block unrelated = soloBlock(state, mint(5, 7));

		state.apply(soloBlock(state, send(state, 1, 2, 1, 0), send(state, 1, 3, 1, 1)));
		mempool.removeCommitted(unrelated, state);

		assertThat(mempool.size(), equalTo(0));
	}
}
