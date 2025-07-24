/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerRoundTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);
	private static final byte[] GRIDNODE_ONE = seed(201);
	private static final byte[] GRIDNODE_TWO = seed(202);

	/* Every seed the tests may need, so the fixtures can sign for whoever is in the set */
	private static List<byte[]> everyone() {
		final List<byte[]> all = new ArrayList<>(FOUNDATION);

		all.add(GRIDNODE_ONE);
		all.add(GRIDNODE_TWO);
		return all;
	}

	private static LedgerState chain() {
		return state(3, ANY_MINT, FOUNDATION);
	}

	private static Vote vote(LedgerState state, byte[] voter, byte[] candidate, Vote.Action action) {
		return Vote.signed(voter, Ed25519.publicKey(candidate), action, state.round(), state.chainId());
	}

	private static void applyBlock(LedgerState state, Transaction... transactions) {
		state.apply(block(state, everyone(), List.of(transactions)));
	}

	private static void applyMint(LedgerState state, int n) {
		applyBlock(state, mint(n, 1));
	}

	/* A full first round: the foundation validators all vote in the two gridnodes, then it runs out */
	private static LedgerState afterRoundOneVotingInBoth() {
		final LedgerState state = chain();

		applyBlock(state, vote(state, seed(1), GRIDNODE_ONE, Vote.Action.ADD),
			vote(state, seed(2), GRIDNODE_ONE, Vote.Action.ADD), vote(state, seed(3), GRIDNODE_ONE, Vote.Action.ADD),
			vote(state, seed(1), GRIDNODE_TWO, Vote.Action.ADD), vote(state, seed(2), GRIDNODE_TWO, Vote.Action.ADD),
			vote(state, seed(3), GRIDNODE_TWO, Vote.Action.ADD));
		applyMint(state, 1);
		applyMint(state, 2);
		return state;
	}

	@Example
	public void shouldCountRoundsInBlocks() {
		final LedgerState state = chain();

		assertThat(state.round(), equalTo(0L));
		applyMint(state, 1);
		applyMint(state, 2);
		assertThat(state.round(), equalTo(0L));
		applyMint(state, 3);
		assertThat(state.round(), equalTo(1L));
	}

	@Example
	public void shouldKeepTheSetFixedWithinTheRound() {
		final LedgerState state = chain();

		applyBlock(state, vote(state, seed(1), GRIDNODE_ONE, Vote.Action.ADD),
			vote(state, seed(2), GRIDNODE_ONE, Vote.Action.ADD), vote(state, seed(3), GRIDNODE_ONE, Vote.Action.ADD));
		assertThat(state.validators().size(), equalTo(3));
	}

	/* In the first round only the foundation validators are in the set, so they alone pick the first gridnodes */
	@Example
	public void shouldAddTheKeysThatMoreThanTwoThirdsVotedForAtTheEndOfTheRound() {
		final LedgerState state = afterRoundOneVotingInBoth();

		assertThat(state.round(), equalTo(1L));
		assertThat(state.validators().keys().subList(0, 3), equalTo(FOUNDATION.stream().map(Ed25519::publicKey)
			.toList()));
		assertThat(state.validators().size(), equalTo(5));
		assertThat(state.validators().contains(Ed25519.publicKey(GRIDNODE_ONE)), is(true));
		assertThat(state.validators().contains(Ed25519.publicKey(GRIDNODE_TWO)), is(true));
		assertThat(state.validators().quorum(), equalTo(4));
	}

	@Example
	public void shouldAppendNewKeysInKeyOrder() {
		final List<AccountKey> added = afterRoundOneVotingInBoth().validators().keys().subList(3, 5);

		assertThat(added, equalTo(added.stream().sorted().toList()));
	}

	@Example
	public void shouldNotAddAKeyThatOnlyTwoThirdsVotedFor() {
		final LedgerState state = chain();

		applyBlock(state, vote(state, seed(1), GRIDNODE_ONE, Vote.Action.ADD),
			vote(state, seed(2), GRIDNODE_ONE, Vote.Action.ADD));
		applyMint(state, 1);
		applyMint(state, 2);

		assertThat(state.round(), equalTo(1L));
		assertThat(state.validators().size(), equalTo(3));
	}

	@Example
	public void shouldCountTheVotesInTheLastBlockOfTheRound() {
		final LedgerState state = chain();

		applyMint(state, 1);
		applyMint(state, 2);
		applyBlock(state, vote(state, seed(1), GRIDNODE_ONE, Vote.Action.ADD),
			vote(state, seed(2), GRIDNODE_ONE, Vote.Action.ADD), vote(state, seed(3), GRIDNODE_ONE, Vote.Action.ADD));

		assertThat(state.validators().contains(Ed25519.publicKey(GRIDNODE_ONE)), is(true));
	}

	@Example
	public void shouldStartEachRoundWithoutVotes() {
		final LedgerState state = afterRoundOneVotingInBoth();

		assertThat(state.votesFor(Ed25519.publicKey(GRIDNODE_ONE), Vote.Action.ADD), equalTo(0));
	}

	@Example
	public void shouldLetAValidatorVoteAgainInTheNextRound() {
		final LedgerState state = chain();
		final byte[] candidate = seed(210);

		applyBlock(state, vote(state, seed(1), candidate, Vote.Action.ADD));
		applyMint(state, 1);
		applyMint(state, 2);

		assertThat(state.rejectionOf(vote(state, seed(1), candidate, Vote.Action.ADD)).isPresent(), is(false));
	}

	@Example
	public void shouldRefuseAVoteForTheRoundThatIsOver() {
		final LedgerState state = chain();
		final Vote old = vote(state, seed(1), seed(210), Vote.Action.ADD);

		applyMint(state, 1);
		applyMint(state, 2);
		applyMint(state, 3);

		assertThat(state.rejectionOf(old).orElse(""), containsString("another round"));
	}

	@Example
	public void shouldRotateTheProposerOverTheNewSet() {
		final LedgerState state = afterRoundOneVotingInBoth();
		final AccountKey proposer = state.validators().proposerAt(4);

		assertThat(proposer, equalTo(state.validators().keys().get(3)));
		assertThat(state.rejectionOf(block(state, everyone(), List.of(mint(5, 1)))).isPresent(), is(false));
	}

	@Example
	public void shouldNeedTheNewQuorumInTheNextRound() {
		final LedgerState state = afterRoundOneVotingInBoth();
		final Block three = block(state, everyone(), 3, state.tipTime(), List.of(mint(5, 1)));
		final Block four = block(state, everyone(), 4, state.tipTime(), List.of(mint(5, 1)));

		assertThat(state.rejectionOf(three).orElse(""), containsString("quorum"));
		assertThat(state.rejectionOf(four).isPresent(), is(false));
	}

	@Example
	public void shouldLetGridnodesVoteAndRemoveAGridnode() {
		final LedgerState state = afterRoundOneVotingInBoth();

		applyBlock(state, vote(state, seed(1), GRIDNODE_ONE, Vote.Action.REMOVE),
			vote(state, seed(2), GRIDNODE_ONE, Vote.Action.REMOVE),
			vote(state, seed(3), GRIDNODE_ONE, Vote.Action.REMOVE),
			vote(state, GRIDNODE_TWO, GRIDNODE_ONE, Vote.Action.REMOVE));
		applyMint(state, 5);
		applyMint(state, 6);

		assertThat(state.validators().contains(Ed25519.publicKey(GRIDNODE_ONE)), is(false));
		assertThat(state.validators().contains(Ed25519.publicKey(GRIDNODE_TWO)), is(true));
		assertThat(state.validators().size(), equalTo(4));
	}

	@Example
	public void shouldNotRemoveAGridnodeThatOnlyTheFoundationVotedOutOfFive() {
		final LedgerState state = afterRoundOneVotingInBoth();

		applyBlock(state, vote(state, seed(1), GRIDNODE_ONE, Vote.Action.REMOVE),
			vote(state, seed(2), GRIDNODE_ONE, Vote.Action.REMOVE),
			vote(state, seed(3), GRIDNODE_ONE, Vote.Action.REMOVE));
		applyMint(state, 5);
		applyMint(state, 6);

		assertThat(state.validators().contains(Ed25519.publicKey(GRIDNODE_ONE)), is(true));
	}

	@Example
	public void shouldCommitTheRootToTheNewSet() {
		final LedgerState withBoth = afterRoundOneVotingInBoth();
		final LedgerState without = chain();

		applyMint(without, 1);
		applyMint(without, 1 + 100);
		applyMint(without, 2 + 100);

		assertThat(withBoth.stateRoot(), not(equalTo(without.stateRoot())));
	}

	/* Whatever is voted, the foundation validators stay and the set stays valid */
	@Property(tries = 20)
	public void shouldNeverLoseAFoundationValidatorOrExceedTheMaximum(@ForAll long seed) {
		final Random random = new Random(seed);
		final LedgerState state = state(2, ANY_MINT, FOUNDATION);
		final List<byte[]> pool = new ArrayList<>(everyone());

		pool.add(seed(203));
		pool.add(seed(204));

		for (int round = 0; round < 6; round++) {
			for (int block = 0; block < 2; block++) {
				final List<Transaction> transactions = new ArrayList<>();

				for (int i = 0; i < 6; i++) {
					final Vote candidate = vote(state, pool.get(random.nextInt(pool.size())),
						pool.get(random.nextInt(pool.size())), random.nextBoolean() ? Vote.Action.ADD
						: Vote.Action.REMOVE);

					if (state.rejectionOf(candidate).isEmpty() && transactions.stream()
						.noneMatch(other -> other.equals(candidate))) {
						transactions.add(candidate);
					}
				}

				transactions.add(mint(round * 2 + block + 1, 1));
				state.apply(block(state, pool, transactions));
			}

			assertThat(state.validators().keys().containsAll(FOUNDATION.stream().map(Ed25519::publicKey).toList()),
				is(true));
			assertThat(state.validators().size(), lessThanOrEqualTo(ValidatorSet.MAX_SIZE));
		}
	}
}
