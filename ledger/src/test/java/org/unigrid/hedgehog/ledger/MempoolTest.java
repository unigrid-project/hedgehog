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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class MempoolTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);

	private static LedgerState chain() {
		return state(2, ANY_MINT, FOUNDATION);
	}

	private static Vote vote(LedgerState state, int voter, int candidate, Vote.Action action) {
		return Vote.signed(seed(voter), key(candidate), action, state.round(), state.chainId());
	}

	private static Heartbeat beat(LedgerState state, int validator, long time) {
		return Heartbeat.signed(seed(validator), state.chainId(), state.height(), state.tipHash(), time);
	}

	@Example
	public void shouldAdmitAValidTransactionOnce() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(mint(1, 5), state), is(true));
		assertThat(mempool.admit(mint(1, 5), state), is(false));
		assertThat(mempool.size(), equalTo(1));
	}

	/* A second mint of the same reference has other fields but could never be applied after the first */
	@Example
	public void shouldDedupeMintsOnTheirReference() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(new Mint(account(1), 5, reference(1)), state), is(true));
		assertThat(mempool.admit(new Mint(account(2), 9, reference(1)), state), is(false));
		assertThat(mempool.size(), equalTo(1));
	}

	@Example
	public void shouldDedupeVotesOnVoterAndCandidate() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(vote(state, 1, 200, Vote.Action.ADD), state), is(true));
		assertThat(mempool.admit(vote(state, 1, 200, Vote.Action.ADD), state), is(false));
		assertThat(mempool.admit(vote(state, 2, 200, Vote.Action.ADD), state), is(true));
		assertThat(mempool.admit(vote(state, 1, 201, Vote.Action.ADD), state), is(true));
		assertThat(mempool.size(), equalTo(3));
	}

	/* Waiting votes count against a validator like cast ones, or a validator could fill the mempool with them */
	@Example
	public void shouldHoldNoMoreVotesFromOneValidatorThanARoundAllows() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();

		for (int i = 0; i < LedgerState.MAX_VOTES_PER_VALIDATOR; i++) {
			assertThat(mempool.admit(vote(state, 1, 300 + i, Vote.Action.ADD), state), is(true));
		}

		assertThat(mempool.admit(vote(state, 1, 999, Vote.Action.ADD), state), is(false));
		assertThat(mempool.admit(vote(state, 2, 999, Vote.Action.ADD), state), is(true));
	}

	@Example
	public void shouldRefuseWhatDoesNotFitWhenFullAndTakeMoreOnceThereIsRoom() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool(3);

		assertThat(mempool.admit(mint(1, 5), state), is(true));
		assertThat(mempool.admit(mint(2, 5), state), is(true));
		assertThat(mempool.admit(mint(3, 5), state), is(true));
		assertThat(mempool.admit(mint(4, 5), state), is(false));

		final Block block = block(state, FOUNDATION, List.of(mint(1, 5)));

		state.apply(block);
		mempool.removeCommitted(block, state);
		assertThat(mempool.admit(mint(4, 5), state), is(true));
	}

	@Example
	public void shouldRefuseWhatTheStateRefuses() {
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(mint(1, 5), soloState(NO_MINT)), is(false));
		assertThat(mempool.size(), equalTo(0));
	}

	@Example
	public void shouldTakeOldestFirstWithoutRemoving() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();
		final Transaction first = mint(1, 5);
		final Transaction second = vote(state, 1, 200, Vote.Action.ADD);
		final Transaction third = mint(2, 5);

		mempool.admit(first, state);
		mempool.admit(second, state);
		mempool.admit(third, state);

		assertThat(mempool.take(2), equalTo(List.of(first, second)));
		assertThat(mempool.take(10), equalTo(List.of(first, second, third)));
		assertThat(mempool.size(), equalTo(3));
	}

	@Example
	public void shouldDropWhatABlockCommitted() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();
		final Transaction mint = mint(1, 5);

		mempool.admit(mint, state);
		mempool.admit(mint(2, 5), state);

		final Block block = block(state, FOUNDATION, List.of(mint));

		state.apply(block);
		mempool.removeCommitted(block, state);
		assertThat(mempool.take(10), equalTo(List.of(mint(2, 5))));
	}

	@Example
	public void shouldDropWhatTheCommittedStateNowRefuses() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();

		mempool.admit(new Mint(account(2), 9, reference(1)), state);
		mempool.admit(vote(state, 1, 200, Vote.Action.ADD), state);

		final Block block = block(state, FOUNDATION, List.of(new Mint(account(1), 5, reference(1)),
			mint(3, 1)));

		state.apply(block);
		mempool.removeCommitted(block, state);

		assertThat(mempool.take(10).stream().anyMatch(Mint.class::isInstance), is(false));
		assertThat(mempool.size(), equalTo(1));
	}

	@Example
	public void shouldAdmitAValidHeartbeatAndKeepTheLatestPerValidator() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(beat(state, 1, 100), state), is(true));
		assertThat(mempool.admit(beat(state, 1, 100), state), is(false));
		assertThat(mempool.admit(beat(state, 1, 90), state), is(false));
		assertThat(mempool.admit(beat(state, 1, 200), state), is(true));
		assertThat(mempool.heartbeats(), hasSize(1));
		assertThat(mempool.heartbeats().get(0).time(), equalTo(200L));
	}

	@Example
	public void shouldRefuseHeartbeatsThatAreNotForTheCurrentTipFromAValidator() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();
		final Heartbeat old = Heartbeat.signed(seed(1), state.chainId(), 5, state.tipHash(), 100);
		final Heartbeat otherTip = Heartbeat.signed(seed(1), state.chainId(), state.height(),
			new byte[Digests.HASH_SIZE], 100);
		final Heartbeat outsider = Heartbeat.signed(seed(99), state.chainId(), state.height(), state.tipHash(), 100);
		final Heartbeat otherChain = Heartbeat.signed(seed(1), new byte[Digests.HASH_SIZE], state.height(),
			state.tipHash(), 100);

		assertThat(mempool.admit(old, state), is(false));
		assertThat(mempool.admit(otherTip, state), is(false));
		assertThat(mempool.admit(outsider, state), is(false));
		assertThat(mempool.admit(otherChain, state), is(false));
		assertThat(mempool.heartbeats(), hasSize(0));
	}

	@Example
	public void shouldBeProgressingWhenAQuorumHasBeenHeardFromRecently() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();

		mempool.admit(beat(state, 1, 1000), state);
		mempool.admit(beat(state, 2, 1000), state);
		assertThat(mempool.isProgressing(state, 1010, 60), is(false));
		mempool.admit(beat(state, 3, 1005), state);
		assertThat(mempool.isProgressing(state, 1010, 60), is(true));
	}

	@Example
	public void shouldStopProgressingWhenTheHeartbeatsGoStale() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();

		mempool.admit(beat(state, 1, 1000), state);
		mempool.admit(beat(state, 2, 1000), state);
		mempool.admit(beat(state, 3, 1000), state);

		assertThat(mempool.isProgressing(state, 1060, 60), is(true));
		assertThat(mempool.isProgressing(state, 1061, 60), is(false));
	}

	@Example
	public void shouldNotCountHeartbeatsFromTheFuture() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();

		mempool.admit(beat(state, 1, 5000), state);
		mempool.admit(beat(state, 2, 5000), state);
		mempool.admit(beat(state, 3, 5000), state);
		assertThat(mempool.isProgressing(state, 1000, 60), is(false));
	}

	/* The chain is quiet, no block has been needed for a long time, and the mempool still shows it is alive */
	@Example
	public void shouldStayProgressingThroughALongQuietSpell() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();

		for (long now = 1000; now <= 4000; now += 30) {
			for (int validator = 1; validator <= 3; validator++) {
				mempool.admit(beat(state, validator, now), state);
			}

			assertThat("at " + now, mempool.isProgressing(state, now, 60), is(true));
		}

		assertThat(state.height(), equalTo(0L));
		assertThat(mempool.isProgressing(state, 4200, 60), is(false));
	}

	/* A heartbeat signed for the tip that a block has since replaced is stale, however genuine it is */
	@Example
	public void shouldRefuseAHeartbeatForThePreviousTipOnceABlockHasLanded() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();
		final Heartbeat beforeTheBlock = beat(state, 1, 1000);
		final Block block = block(state, FOUNDATION, List.of(mint(1, 5)));

		state.apply(block);
		mempool.removeCommitted(block, state);

		assertThat(mempool.admit(beforeTheBlock, state), is(false));
		assertThat(mempool.admit(beat(state, 1, 1001), state), is(true));
	}

	@Example
	public void shouldForgetHeartbeatsWhenABlockMovesTheTip() {
		final LedgerState state = chain();
		final Mempool mempool = new Mempool();

		for (int validator = 1; validator <= 3; validator++) {
			mempool.admit(beat(state, validator, 1000), state);
		}

		assertThat(mempool.isProgressing(state, 1000, 60), is(true));

		final Block block = block(state, FOUNDATION, List.of(mint(1, 5)));

		state.apply(block);
		mempool.removeCommitted(block, state);
		assertThat(mempool.heartbeats(), hasSize(0));
		assertThat(mempool.isProgressing(state, 1000, 60), is(false));
	}
}
