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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerRoundTest {
	private static final long ROUND = 60_000;
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
		return state(ROUND, ANY_MINT, FOUNDATION);
	}

	private static long startOf(long round) {
		return GENESIS_TIME + round * ROUND;
	}

	private static String reasonOf(Optional<String> rejection) {
		return rejection.orElse("");
	}

	private static Vote vote(LedgerState state, long round, byte[] voter, byte[] candidate, Vote.Action action) {
		return Vote.signed(voter, Ed25519.publicKey(candidate), action, round, state.chainId());
	}

	private static List<Transaction> allFoundationVotes(LedgerState state, long round, byte[] candidate,
		Vote.Action action) {
		return FOUNDATION.stream().<Transaction>map(voter -> vote(state, round, voter, candidate, action)).toList();
	}

	/* Signed by every validator of the set the state is in, which is the set of the block's parent */
	private static Block blockAt(LedgerState state, long time, List<Transaction> transactions) {
		return block(state, everyone(), state.validators().size(), time, transactions);
	}

	private static void applyAt(LedgerState state, long time, Transaction... transactions) {
		state.apply(blockAt(state, time, List.of(transactions)));
	}

	private static void applyAt(LedgerState state, long time, List<Transaction> transactions) {
		state.apply(blockAt(state, time, transactions));
	}

	/* The foundation validators vote both gridnodes in during round 0, and a block of round 1 closes it */
	private static LedgerState afterRoundZeroVotingInBoth() {
		final LedgerState state = chain();
		final List<Transaction> votes = new ArrayList<>(allFoundationVotes(state, 0, GRIDNODE_ONE,
			Vote.Action.ADD));

		votes.addAll(allFoundationVotes(state, 0, GRIDNODE_TWO, Vote.Action.ADD));
		applyAt(state, startOf(0), votes);
		applyAt(state, startOf(1), mint(1, 1));
		return state;
	}

	@Example
	public void shouldCountRoundsInTimeFromTheGenesis() {
		final LedgerState state = chain();

		assertThat(state.roundAt(GENESIS_TIME), equalTo(0L));
		assertThat(state.roundAt(GENESIS_TIME + ROUND - 1), equalTo(0L));
		assertThat(state.roundAt(GENESIS_TIME + ROUND), equalTo(1L));
		assertThat(state.roundAt(GENESIS_TIME + 7 * ROUND + 5), equalTo(7L));
	}

	/* A round ends where the next starts; one too long for a long to count its end never ends */
	@Example
	public void shouldEndARoundWhereTheNextStarts() {
		final LedgerState state = chain();

		assertThat(state.roundEndAt(startOf(3) + 5), equalTo(startOf(4)));
		assertThat(state.roundEndAt(startOf(4)), equalTo(startOf(5)));
		assertThat(state(Long.MAX_VALUE, ANY_MINT, FOUNDATION).roundEndAt(GENESIS_TIME), equalTo(Long.MAX_VALUE));
	}

	@Example
	public void shouldBeInTheRoundOfTheTimeOfTheTip() {
		final LedgerState state = chain();

		assertThat(state.round(), equalTo(0L));
		applyAt(state, startOf(1) - 1, mint(1, 1));
		assertThat(state.round(), equalTo(0L));
		applyAt(state, startOf(3) + 5, mint(2, 1));
		assertThat(state.round(), equalTo(3L));
	}

	/* However many blocks a round holds, its votes wait for its time to run out */
	@Example
	public void shouldKeepTheSetFixedWithinTheRound() {
		final LedgerState state = chain();

		applyAt(state, startOf(0), allFoundationVotes(state, 0, GRIDNODE_ONE, Vote.Action.ADD));

		for (int n = 1; n <= 5; n++) {
			applyAt(state, startOf(1) - 1, mint(n, 1));
		}

		assertThat(state.validators().size(), equalTo(3));
		assertThat(state.votesFor(Ed25519.publicKey(GRIDNODE_ONE), Vote.Action.ADD), equalTo(3));
	}

	/* In the first round only the foundation validators are in the set, so they alone pick the first gridnodes */
	@Example
	public void shouldAddTheKeysThatMoreThanTwoThirdsVotedForWhenABlockOfALaterRoundComes() {
		final LedgerState state = afterRoundZeroVotingInBoth();

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
		final List<AccountKey> added = afterRoundZeroVotingInBoth().validators().keys().subList(3, 5);

		assertThat(added, equalTo(added.stream().sorted().toList()));
	}

	@Example
	public void shouldNotAddAKeyThatOnlyTwoThirdsVotedFor() {
		final LedgerState state = chain();

		applyAt(state, startOf(0), vote(state, 0, seed(1), GRIDNODE_ONE, Vote.Action.ADD),
			vote(state, 0, seed(2), GRIDNODE_ONE, Vote.Action.ADD));
		applyAt(state, startOf(1), mint(1, 1));

		assertThat(state.round(), equalTo(1L));
		assertThat(state.validators().size(), equalTo(3));
	}

	/* The votes in the block that closes a round are for the round it opens, so they are not counted with the votes
	   of the round it closes */
	@Example
	public void shouldTallyTheRoundBeforeTheTransactionsOfTheBlockThatClosesIt() {
		final LedgerState state = chain();

		applyAt(state, startOf(0), vote(state, 0, seed(1), GRIDNODE_ONE, Vote.Action.ADD),
			vote(state, 0, seed(2), GRIDNODE_ONE, Vote.Action.ADD));
		applyAt(state, startOf(1), vote(state, 1, seed(3), GRIDNODE_ONE, Vote.Action.ADD));

		assertThat(state.validators().size(), equalTo(3));
		assertThat(state.votesFor(Ed25519.publicKey(GRIDNODE_ONE), Vote.Action.ADD), equalTo(1));
	}

	/* Rounds without a block hold no votes, so the block that ends a quiet spell counts the last votes once */
	@Example
	public void shouldCloseTheRoundsThatPassedWithoutABlockAtTheNextBlock() {
		final LedgerState state = chain();

		applyAt(state, startOf(0), allFoundationVotes(state, 0, GRIDNODE_ONE, Vote.Action.ADD));
		applyAt(state, startOf(5) + 9, mint(1, 1));

		assertThat(state.round(), equalTo(5L));
		assertThat(state.validators().size(), equalTo(4));
		assertThat(state.votesFor(Ed25519.publicKey(GRIDNODE_ONE), Vote.Action.ADD), equalTo(0));
	}

	/* A quiet chain makes no blocks, so a round that holds votes is closed by a block with nothing in it once its time
	   is over */
	@Example
	public void shouldTakeAnEmptyBlockThatClosesARoundThatHoldsVotes() {
		final LedgerState state = chain();

		applyAt(state, startOf(0), allFoundationVotes(state, 0, GRIDNODE_ONE, Vote.Action.ADD));

		final Block closing = blockAt(state, startOf(1), List.of());

		assertThat(state.rejectionOf(closing).isPresent(), is(false));
		assertThat(state.proposalRejection(closing).isPresent(), is(false));
		state.apply(closing);
		assertThat(state.validators().contains(Ed25519.publicKey(GRIDNODE_ONE)), is(true));
	}

	@Example
	public void shouldRefuseAnEmptyBlockBeforeTheTimeOfTheRoundIsOver() {
		final LedgerState state = chain();

		applyAt(state, startOf(0), allFoundationVotes(state, 0, GRIDNODE_ONE, Vote.Action.ADD));

		assertThat(reasonOf(state.rejectionOf(blockAt(state, startOf(1) - 1, List.of()))),
			containsString("1 to " + Block.MAX_TRANSACTIONS));
	}

	@Example
	public void shouldRefuseAnEmptyBlockThatClosesARoundWithoutVotes() {
		final LedgerState state = chain();

		applyAt(state, startOf(0), mint(1, 1));

		assertThat(reasonOf(state.rejectionOf(blockAt(state, startOf(1), List.of()))),
			containsString("1 to " + Block.MAX_TRANSACTIONS));
	}

	/* The block that closes the round takes its votes with it, so it is the one empty block the round gets */
	@Example
	public void shouldTakeOneEmptyBlockToCloseARoundAndNoMore() {
		final LedgerState state = chain();

		applyAt(state, startOf(0), allFoundationVotes(state, 0, GRIDNODE_ONE, Vote.Action.ADD));
		applyAt(state, startOf(1), List.of());

		assertThat(reasonOf(state.rejectionOf(blockAt(state, startOf(1) + 1, List.of()))),
			containsString("1 to " + Block.MAX_TRANSACTIONS));
		assertThat(reasonOf(state.rejectionOf(blockAt(state, startOf(5), List.of()))),
			containsString("1 to " + Block.MAX_TRANSACTIONS));
	}

	/* Whether a vote fits its block is a matter of the time of the block, which every node reads the same */
	@Example
	public void shouldTakeAVoteOnlyInABlockOfItsRound() {
		final LedgerState state = chain();
		final Block due = blockAt(state, startOf(1), List.of(vote(state, 1, seed(1), GRIDNODE_ONE,
			Vote.Action.ADD)));
		final Block early = due.toBuilder().time(startOf(1) - 1).build();

		assertThat(state.proposalRejection(due).isPresent(), is(false));
		assertThat(reasonOf(state.proposalRejection(early)), containsString("another round"));
		assertThrows(IllegalArgumentException.class, () -> state.rootAfter(1, startOf(1),
			List.of(vote(state, 0, seed(1), GRIDNODE_ONE, Vote.Action.ADD))));
	}

	@Example
	public void shouldStartEachRoundWithoutVotes() {
		final LedgerState state = afterRoundZeroVotingInBoth();

		assertThat(state.votesFor(Ed25519.publicKey(GRIDNODE_ONE), Vote.Action.ADD), equalTo(0));
	}

	@Example
	public void shouldLetAValidatorVoteAgainInTheNextRound() {
		final LedgerState state = chain();
		final byte[] candidate = seed(210);

		applyAt(state, startOf(0), vote(state, 0, seed(1), candidate, Vote.Action.ADD));
		applyAt(state, startOf(1), mint(1, 1));

		assertThat(state.rejectionOf(vote(state, 1, seed(1), candidate, Vote.Action.ADD)).isPresent(), is(false));
	}

	@Example
	public void shouldRefuseAVoteForTheRoundThatIsOver() {
		final LedgerState state = chain();
		final Vote old = vote(state, 0, seed(1), seed(210), Vote.Action.ADD);

		applyAt(state, startOf(1), mint(1, 1));

		assertThat(reasonOf(state.rejectionOf(old)), containsString("another round"));
	}

	@Example
	public void shouldRotateTheProposerOverTheNewSet() {
		final LedgerState state = afterRoundZeroVotingInBoth();

		applyAt(state, startOf(1), mint(5, 1));

		assertThat(state.validators().proposerAt(4), equalTo(state.validators().keys().get(3)));
		assertThat(state.rejectionOf(blockAt(state, startOf(1), List.of(mint(6, 1)))).isPresent(), is(false));
	}

	@Example
	public void shouldNeedTheNewQuorumInTheNextRound() {
		final LedgerState state = afterRoundZeroVotingInBoth();
		final Block three = block(state, everyone(), 3, state.tipTime(), List.of(mint(5, 1)));
		final Block four = block(state, everyone(), 4, state.tipTime(), List.of(mint(5, 1)));

		assertThat(reasonOf(state.rejectionOf(three)), containsString("quorum"));
		assertThat(state.rejectionOf(four).isPresent(), is(false));
	}

	/* The new set takes over from the height after the block that closes the round, which the set of its parent signs
	   and proposes */
	@Example
	public void shouldHaveTheBlockThatClosesARoundSignedByTheSetBeforeIt() {
		final LedgerState state = chain();

		applyAt(state, startOf(0), allFoundationVotes(state, 0, GRIDNODE_ONE, Vote.Action.ADD));

		final Block closing = block(state, FOUNDATION, 3, startOf(1), List.of(mint(1, 1)));

		assertThat(state.rejectionOf(closing).isPresent(), is(false));
		state.apply(closing);
		assertThat(state.previousValidators().size(), equalTo(3));
		assertThat(state.validators().size(), equalTo(4));
		assertThat(state.tipCertificate().endorsements().size(), equalTo(3));
	}

	@Example
	public void shouldLetGridnodesVoteAndRemoveAGridnode() {
		final LedgerState state = afterRoundZeroVotingInBoth();
		final List<Transaction> removals = new ArrayList<>(allFoundationVotes(state, 1, GRIDNODE_ONE,
			Vote.Action.REMOVE));

		removals.add(vote(state, 1, GRIDNODE_TWO, GRIDNODE_ONE, Vote.Action.REMOVE));
		applyAt(state, startOf(1) + 1, removals);
		applyAt(state, startOf(2), mint(5, 1));

		assertThat(state.validators().contains(Ed25519.publicKey(GRIDNODE_ONE)), is(false));
		assertThat(state.validators().contains(Ed25519.publicKey(GRIDNODE_TWO)), is(true));
		assertThat(state.validators().size(), equalTo(4));
	}

	@Example
	public void shouldNotRemoveAGridnodeThatOnlyTheFoundationVotedOutOfFive() {
		final LedgerState state = afterRoundZeroVotingInBoth();

		applyAt(state, startOf(1) + 1, allFoundationVotes(state, 1, GRIDNODE_ONE, Vote.Action.REMOVE));
		applyAt(state, startOf(2), mint(5, 1));

		assertThat(state.validators().contains(Ed25519.publicKey(GRIDNODE_ONE)), is(true));
	}

	/* Both states are at height 2 with the same account, no open votes and the same set in the same order; only the
	   set that signed the tip differs, because one set has just grown and the other has not */
	@Example
	public void shouldCommitTheRootToTheSetThatSignedTheTip() {
		final List<byte[]> four = seeds(1, 4);
		final List<byte[]> five = new ArrayList<>(four);

		five.add(seed(200));

		final LedgerState grown = state(ROUND, ANY_MINT, four);
		final LedgerState unchanged = state(ROUND, ANY_MINT, five);

		grown.apply(block(grown, four, List.of(
			Vote.signed(seed(1), key(200), Vote.Action.ADD, 0, grown.chainId()),
			Vote.signed(seed(2), key(200), Vote.Action.ADD, 0, grown.chainId()),
			Vote.signed(seed(3), key(200), Vote.Action.ADD, 0, grown.chainId()))));
		grown.apply(block(grown, four, 4, startOf(1), List.of(mint(1, 1))));
		unchanged.apply(block(unchanged, five, List.of(
			Vote.signed(seed(1), key(201), Vote.Action.ADD, 0, unchanged.chainId()))));
		unchanged.apply(block(unchanged, five, 5, startOf(1), List.of(mint(1, 1))));

		assertThat(grown.height(), equalTo(unchanged.height()));
		assertThat(grown.validators(), equalTo(unchanged.validators()));
		assertThat(grown.votesFor(key(200), Vote.Action.ADD), equalTo(0));
		assertThat(unchanged.votesFor(key(201), Vote.Action.ADD), equalTo(0));
		assertThat(grown.previousValidators(), not(equalTo(unchanged.previousValidators())));
		assertThat(grown.stateRoot(), not(equalTo(unchanged.stateRoot())));
	}

	/* Whatever is voted and however far apart the blocks come, the foundation validators stay and the set stays
	   valid */
	@Property(tries = 20)
	public void shouldNeverLoseAFoundationValidatorOrExceedTheMaximum(@ForAll long seed) {
		final Random random = new Random(seed);
		final LedgerState state = chain();
		final List<byte[]> pool = new ArrayList<>(everyone());
		long time = GENESIS_TIME;

		pool.add(seed(203));
		pool.add(seed(204));

		for (int block = 1; block <= 12; block++) {
			final List<Transaction> candidates = new ArrayList<>();

			time += random.nextLong(2 * ROUND);

			for (int i = 0; i < 6; i++) {
				candidates.add(vote(state, state.roundAt(time), pool.get(random.nextInt(pool.size())),
					pool.get(random.nextInt(pool.size())), random.nextBoolean() ? Vote.Action.ADD
					: Vote.Action.REMOVE));
			}

			candidates.add(mint(block, 1));
			state.apply(block(state, pool, state.validators().size(), time, state.applicable(time, candidates)));

			assertThat(state.validators().keys().containsAll(FOUNDATION.stream().map(Ed25519::publicKey).toList()),
				is(true));
			assertThat(state.validators().size(), lessThanOrEqualTo(ValidatorSet.MAX_SIZE));
		}
	}
}
