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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Action.*;
import static org.unigrid.hedgehog.ledger.ConsensusFixtures.*;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class ConsensusViewsTest {
	/* The engine is validator 4, who proposes view 3 and follows in views 0, 1 and 2, with the validators 1, 2 and 3
	   as the others. The leader is validator 2, who proposes view 1, with 1, 3 and 4 as the others. */
	private static final Timeouts TIMEOUTS = new Timeouts(3000, 500, 1000, 300, 1500, 700);

	private final ValidatorSet validators = validators(4);
	private final FakeApp app = new FakeApp(validators, key(4), 3);
	private final Consensus engine = new Consensus(app, CHAIN, seed(4), TIMEOUTS);
	private final FakeApp leaderApp = new FakeApp(validators, key(2), 3);
	private final Consensus leader = new Consensus(leaderApp, CHAIN, seed(2), TIMEOUTS);

	/* Both applications have the same empty chain, so a block built by one is right for the other */
	private Block proposed(int view, String note) {
		return app.block(1, view, validators.proposerAt(1, view), 100, note);
	}

	private static Block certified(Block block) {
		return block.endorsedBy(seed(1), CHAIN).endorsedBy(seed(2), CHAIN).endorsedBy(seed(3), CHAIN);
	}

	private static boolean schedules(List<Action> actions, Timeout.Kind kind) {
		return actions.stream().anyMatch(a -> a instanceof Schedule s && s.timeout().kind() == kind);
	}

	private static Ballot prevoteOf(List<Action> actions) {
		return ballots(actions).stream().filter(b -> b.type() == Ballot.Type.PREVOTE).findFirst().orElseThrow();
	}

	/* The node sees the proposal of the view and more than 2/3 of the prevotes for it, so it locks on the block */
	private void lockOn(int view, int validRound, Block block) {
		engine.onMessage(proposal(view, validRound, block), 100 + view);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, view, block), 100 + view);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, view, block), 100 + view);
	}

	private static Ballot prevoteBy(int validator, int view, Block block) {
		return ballot(Ballot.Type.PREVOTE, validator, view, block);
	}

	private static List<Ballot> prevotesOf(Block block, int view, int... voters) {
		return Arrays.stream(voters).mapToObj(voter -> prevoteBy(voter, view, block)).toList();
	}

	/* A prevote of view 1 that claims to be from one validator and carries the signature of another */
	private static Ballot forgedPrevote(int claimed, int signer, Block block) {
		return new Ballot(Ballot.Type.PREVOTE, 1, 1, block.hash(), key(claimed),
			prevoteBy(signer, 1, block).signature());
	}

	/* A new block from the proposer of the view, with a signature that is not the proposer's */
	private Proposal forgedProposal(int view) {
		final AccountKey proposer = validators.proposerAt(1, view);

		return new Proposal(1, view, -1, app.block(1, view, proposer, 100, "forged"), List.of(), proposer,
			new byte[Ed25519.SIGNATURE_SIZE]);
	}

	/* A proof of the prevotes of view 1 in which the third ballot carries the signature of another validator */
	private static List<Ballot> withForgedThird(Block block) {
		return List.of(prevoteBy(1, 1, block), prevoteBy(2, 1, block), forgedPrevote(3, 4, block));
	}

	/* The node has seen nothing of view 1: two validators that are already in view 2 take it there */
	private void inViewTwo() {
		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 2, null), 20);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 2, null), 21);

		assertThat(engine.view(), equalTo(2));
	}

	/* A proposal whose block cannot be proven yet is neither voted for nor voted against at once: the node waits for
	   the proof to turn up, and gives up on the block when the time for the proposal of the view is over */
	private void prevotesNilOnlyWhenTheProposeTimerFires(Proposal proposal) {
		assertThat(ballots(engine.onMessage(proposal, 6000)).isEmpty(), is(true));
		assertThat(engine.step(), equalTo(Consensus.Step.PROPOSE));
		assertThat(prevoteOf(engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 2), 9000)).isNil(), is(true));
	}

	/* More than 2/3 of the precommits are in, none of them decisive, so the precommit timeout takes the node on */
	private void endView(int view) {
		for (int validator = 1; validator <= 3; validator++) {
			engine.onMessage(ballot(Ballot.Type.PRECOMMIT, validator, view, null), 200 + view);
		}

		engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, view), 300 + view);
	}

	@Example
	public void shouldRunNothingWhileThereIsNoWorkAndNoMessage() {
		app.goal = 0;

		assertThat(engine.start(1, validators, 0).isEmpty(), is(true));
		assertThat(engine.onWork(5).isEmpty(), is(true));
		assertThat(engine.resume(9).isEmpty(), is(true));
	}

	@Example
	public void shouldStartTheFirstViewWhenWorkTurnsUp() {
		app.goal = 0;
		engine.start(1, validators, 0);
		app.goal = 3;

		final List<Action> actions = engine.onWork(5);

		assertThat(actions, equalTo(List.of(schedule(Timeout.Kind.PROPOSE, 0, 3000))));
		assertThat(engine.onWork(6).isEmpty(), is(true));
	}

	@Example
	public void shouldProposeWhenWorkTurnsUpInTheViewItProposes() {
		leaderApp.goal = 0;
		leader.start(1, validators, 0);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 1, 1, null), 3001);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 3, 1, null), 3002);

		assertThat(leader.view(), equalTo(1));
		assertThat(leader.onWork(3003).isEmpty(), is(true));

		leaderApp.goal = 3;

		final List<Action> actions = leader.onWork(3004);

		assertThat(proposalsSent(actions).size(), equalTo(1));
		assertThat(proposalsSent(actions).get(0).view(), equalTo(1));
		assertThat(leader.onWork(3005).isEmpty(), is(true));
	}

	@Example
	public void shouldStartTheFirstViewOnAnyValidMessageOfItsHeight() {
		app.goal = 0;
		engine.start(1, validators, 0);

		final Block block = proposed(0, "a");
		final List<Action> actions = engine.onMessage(proposal(0, -1, block), 5);

		assertThat(schedules(actions, Timeout.Kind.PROPOSE), is(true));
		assertThat(ballots(actions).size(), equalTo(1));
	}

	@Example
	public void shouldPrevoteNilWhenTheProposeTimeoutFires() {
		engine.start(1, validators, 0);

		final List<Ballot> out = ballots(engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000));

		assertThat(out.size(), equalTo(1));
		assertThat(out.get(0).isNil(), is(true));
		assertThat(engine.step(), equalTo(Consensus.Step.PREVOTE));
	}

	@Example
	public void shouldIgnoreATimeoutOfAnotherViewHeightOrStep() {
		engine.start(1, validators, 0);

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 4), 1).isEmpty(), is(true));
		assertThat(engine.onTimeout(new Timeout(Timeout.Kind.PROPOSE, 2, 0), 1).isEmpty(), is(true));
		assertThat(engine.onTimeout(timeout(Timeout.Kind.PREVOTE, 0), 1).isEmpty(), is(true));
		assertThat(engine.step(), equalTo(Consensus.Step.PROPOSE));
	}

	@Example
	public void shouldIgnoreATimeoutBeforeAnyViewRunsAndAfterTheDecision() {
		app.goal = 0;
		engine.start(1, validators, 0);

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 1).isEmpty(), is(true));
		assertThat(engine.step(), equalTo(Consensus.Step.PROPOSE));

		final Block block = proposed(0, "a");

		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, block), 11);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 0, block), 12);

		assertThat(decides(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, block), 13)), is(true));
		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 14).isEmpty(), is(true));
		assertThat(engine.view(), equalTo(0));
	}

	@Example
	public void shouldWaitForMoreVotesAfterAQuorumOfMixedPrevotesAndThenPrecommitNil() {
		engine.start(1, validators, 0);
		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, proposed(0, "a")), 3001);

		final List<Action> armed = engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 0, null), 3002);

		assertThat(schedules(armed, Timeout.Kind.PREVOTE), is(true));

		final List<Ballot> out = ballots(engine.onTimeout(timeout(Timeout.Kind.PREVOTE, 0), 4002));

		assertThat(out.get(0).type(), equalTo(Ballot.Type.PRECOMMIT));
		assertThat(out.get(0).isNil(), is(true));
		assertThat(engine.step(), equalTo(Consensus.Step.PRECOMMIT));
	}

	@Example
	public void shouldArmThePrevoteTimerOnlyOncePerView() {
		engine.start(1, validators, 0);
		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, proposed(0, "a")), 3001);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 0, null), 3002);

		assertThat(schedules(engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, proposed(0, "b")), 3003),
			Timeout.Kind.PREVOTE), is(false));
	}

	@Example
	public void shouldArmThePrevoteTimerOnlyOnceItHasPrevoted() {
		engine.start(1, validators, 0);

		assertThat(schedules(engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, proposed(0, "a")), 5),
			Timeout.Kind.PREVOTE), is(false));
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 0, null), 6);
		assertThat(schedules(engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, proposed(0, "b")), 7),
			Timeout.Kind.PREVOTE), is(false));
		assertThat(schedules(engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000), Timeout.Kind.PREVOTE),
			is(true));
	}

	@Example
	public void shouldNotWaitForMoreVotesOnceItHasPrecommitted() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, block), 11);

		final List<Action> locked = engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 0, block), 12);

		assertThat(ballots(locked).get(0).type(), equalTo(Ballot.Type.PRECOMMIT));
		assertThat(schedules(locked, Timeout.Kind.PREVOTE), is(false));
	}

	@Example
	public void shouldMoveToTheNextViewWhenThePrecommitTimeoutFiresAndProposeIfItIsTheProposer() {
		leader.start(1, validators, 0);
		leader.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, null), 3001);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, null), 3002);
		leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, null), 3003);

		final List<Action> armed = leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, null), 3004);

		assertThat(schedules(armed, Timeout.Kind.PRECOMMIT), is(true));

		final List<Action> next = leader.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 4005);

		assertThat(leader.view(), equalTo(1));
		assertThat(leader.step(), equalTo(Consensus.Step.PREVOTE));
		assertThat(proposalsSent(next).size(), equalTo(1));
		assertThat(schedules(next, Timeout.Kind.PROPOSE), is(true));
	}

	@Example
	public void shouldArmThePrecommitTimerOnlyOnTheFirstQuorumOfPrecommits() {
		engine.start(1, validators, 0);

		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, null), 5);

		assertThat(schedules(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 0, null), 6),
			Timeout.Kind.PRECOMMIT), is(false));
		assertThat(schedules(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, null), 7),
			Timeout.Kind.PRECOMMIT), is(true));
		assertThat(schedules(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 4, 0, null), 8),
			Timeout.Kind.PRECOMMIT), is(false));
	}

	@Example
	public void shouldNotMoveBeyondTheLastViewWhenItsPrecommitTimeoutFires() {
		final int last = Integer.MAX_VALUE;

		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, last, null), 5);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, last, null), 6);

		assertThat(engine.view(), equalTo(last));

		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, last, null), 7);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, last, null), 8);

		assertThat(schedules(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, last, null), 9),
			Timeout.Kind.PRECOMMIT), is(true));
		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, last), 10).isEmpty(), is(true));
		assertThat(engine.view(), equalTo(last));
	}

	@Example
	public void shouldScheduleTheProposeTimeoutWithADelayThatGrowsWithTheView() {
		assertThat(engine.start(1, validators, 0), hasItem(schedule(Timeout.Kind.PROPOSE, 0, 3000)));
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 2, null), 20);

		assertThat(engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 2, null), 21),
			hasItem(schedule(Timeout.Kind.PROPOSE, 2, 4000)));
	}

	@Example
	public void shouldScheduleThePrevoteTimeoutWithADelayThatGrowsWithTheView() {
		engine.start(1, validators, 0);
		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, proposed(0, "a")), 3001);

		assertThat(engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 0, null), 3002),
			hasItem(schedule(Timeout.Kind.PREVOTE, 0, 1000)));

		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 2, proposed(2, "a")), 20);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 2, null), 21);

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 2), 9000),
			hasItem(schedule(Timeout.Kind.PREVOTE, 2, 1600)));
	}

	@Example
	public void shouldScheduleThePrecommitTimeoutWithADelayThatGrowsWithTheView() {
		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, null), 5);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 0, null), 6);

		assertThat(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, null), 7),
			hasItem(schedule(Timeout.Kind.PRECOMMIT, 0, 1500)));

		inViewTwo();
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 2, null), 22);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 2, null), 23);

		assertThat(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 2, null), 24),
			hasItem(schedule(Timeout.Kind.PRECOMMIT, 2, 2900)));
	}

	@Example
	public void shouldSkipToTheViewMoreThanOneThirdHaveReached() {
		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 3, null), 5);

		final List<Action> actions = engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 3, null), 6);

		assertThat(engine.view(), equalTo(3));
		assertThat(actions.stream().anyMatch(a -> a instanceof Schedule s
			&& s.timeout().equals(timeout(Timeout.Kind.PROPOSE, 3))), is(true));
	}

	@Example
	public void shouldNotSkipToTheViewItIsInOrToAnEarlierOne() {
		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 3, null), 5);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 3, null), 6);

		assertThat(engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 1, null), 7).isEmpty(), is(true));
		assertThat(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 1, null), 8).isEmpty(), is(true));
		assertThat(engine.view(), equalTo(3));
	}

	@Example
	public void shouldNotSkipOnTheWordOfASingleValidator() {
		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 3, null), 5);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 5, null), 6);

		assertThat(engine.view(), equalTo(0));
	}

	@Example
	public void shouldSkipOnTheProposalsAloneOfMoreThanOneThirdAtALaterView() {
		engine.start(1, validators, 0);
		engine.onMessage(proposal(1, -1, proposed(1, "a")), 5);

		assertThat(engine.view(), equalTo(0));

		engine.onMessage(proposal(2, -1, proposed(2, "b")), 6);

		assertThat(engine.view(), equalTo(1));
	}

	@Example
	public void shouldNotMoveOnForgedProposalsFarAhead() {
		engine.start(1, validators, 0);

		assertThat(engine.onMessage(forgedProposal(Consensus.MAX_VIEW_AHEAD + 1), 5).isEmpty(), is(true));
		assertThat(engine.onMessage(forgedProposal(Consensus.MAX_VIEW_AHEAD + 2), 6).isEmpty(), is(true));
		assertThat(engine.view(), equalTo(0));
	}

	@Example
	public void shouldOnlyRememberAViewFarAheadAndNotStoreIt() {
		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 500, null), 5);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 500, null), 6);

		assertThat(engine.view(), equalTo(500));
		assertThat(engine.storedBallots(), equalTo(0));
	}

	@Example
	public void shouldStayLockedOnTheBlockItPrecommittedAndPrevoteNilForAnother() {
		final Block a = proposed(0, "a");
		final Block b = proposed(1, "b");

		engine.start(1, validators, 0);
		lockOn(0, -1, a);
		endView(0);

		assertThat(engine.view(), equalTo(1));
		assertThat(engine.lockedView(), equalTo(0));

		assertThat(prevoteOf(engine.onMessage(proposal(1, -1, b), 5001)).isNil(), is(true));
	}

	@Example
	public void shouldPrevoteANewBlockOfALaterViewWhenItIsNotLocked() {
		final Block b = proposed(1, "b");

		engine.start(1, validators, 0);
		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		endView(0);

		assertThat(engine.view(), equalTo(1));
		assertThat(engine.lockedView(), equalTo(-1));
		assertThat(prevoteOf(engine.onMessage(proposal(1, -1, b), 5001)).blockHash(), equalTo(b.hash()));
	}

	@Example
	public void shouldPrevoteTheLockedBlockWhenItIsPutForwardFromTheViewOfItsLock() {
		final Block a = proposed(0, "a");

		engine.start(1, validators, 0);
		lockOn(0, -1, a);
		endView(0);

		final Ballot prevote = prevoteOf(engine.onMessage(proposal(1, 0, a), 5001));

		assertThat(prevote.view(), equalTo(1));
		assertThat(prevote.blockHash(), equalTo(a.hash()));
	}

	@Example
	public void shouldPrevoteTheLockedBlockWhenItIsPutForwardFromAnEarlierViewThanItsLock() {
		final Block a = proposed(0, "a");

		engine.start(1, validators, 0);
		lockOn(0, -1, a);
		endView(0);
		lockOn(1, 0, a);
		endView(1);

		assertThat(engine.view(), equalTo(2));
		assertThat(engine.lockedView(), equalTo(1));

		final Ballot prevote = prevoteOf(engine.onMessage(proposal(2, 0, a), 5001));

		assertThat(prevote.view(), equalTo(2));
		assertThat(prevote.blockHash(), equalTo(a.hash()));
	}

	@Example
	public void shouldPrevoteNilForAnotherBlockWhenTheLockIsLaterThanThePrevoteQuorumThatBlockNames() {
		final Block b = proposed(0, "b");
		final Block c = proposed(1, "c");

		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, b), 10);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 0, b), 11);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, b), 12);
		endView(0);
		lockOn(1, -1, c);
		endView(1);

		assertThat(engine.view(), equalTo(2));
		assertThat(engine.lockedView(), equalTo(1));
		assertThat(prevoteOf(engine.onMessage(proposal(2, 0, b), 5001)).isNil(), is(true));
	}

	@Example
	public void shouldPrevoteTheBlockOfALaterViewOnceTheQuorumItNamesIsThere() {
		final Block b = proposed(0, "b");

		engine.start(1, validators, 0);
		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		endView(0);

		assertThat(engine.onMessage(proposal(1, 0, b), 5001).stream().noneMatch(a -> a instanceof Broadcast),
			is(true));
		assertThat(engine.step(), equalTo(Consensus.Step.PROPOSE));

		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, b), 5002);
		assertThat(engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 0, b), 5003).isEmpty(), is(true));

		final Ballot prevote = prevoteOf(engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, b), 5004));

		assertThat(prevote.view(), equalTo(1));
		assertThat(prevote.blockHash(), equalTo(b.hash()));
	}

	@Example
	public void shouldIgnoreAReProposalOfABlockWhoseHeaderIsOfALaterViewThanTheQuorumItNames() {
		final Block b = proposed(1, "b");

		engine.start(1, validators, 0);
		engine.onMessage(prevoteBy(1, 0, b), 10);
		engine.onMessage(prevoteBy(2, 0, b), 11);
		engine.onMessage(prevoteBy(3, 0, b), 12);
		engine.onMessage(prevoteBy(1, 2, null), 20);
		engine.onMessage(prevoteBy(2, 2, null), 21);

		assertThat(engine.view(), equalTo(2));
		assertThat(engine.onMessage(proposal(2, 0, b), 6000).isEmpty(), is(true));
		assertThat(engine.step(), equalTo(Consensus.Step.PROPOSE));
	}

	@Example
	public void shouldPutForwardAgainTheBlockItSawAPrevoteQuorumForWhenItIsTheProposer() {
		final Block a = proposed(0, "a");

		leader.start(1, validators, 0);
		leader.onMessage(proposal(0, -1, a), 10);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, a), 11);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, a), 12);
		leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, null), 13);
		leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, null), 14);
		leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 4, 0, null), 15);

		final List<Action> actions = leader.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 5000);
		final Proposal again = proposalsSent(actions).get(0);

		assertThat(again.block().hash(), equalTo(a.hash()));
		assertThat(again.block().getView(), equalTo(0));
		assertThat(again.validRound(), equalTo(0));
		assertThat(again.view(), equalTo(1));

		engine.start(1, validators, 0);
		lockOn(0, -1, a);
		endView(0);

		assertThat(prevoteOf(engine.onMessage(again, 5001)).blockHash(), equalTo(a.hash()));
	}

	@Example
	public void shouldPrevoteABlockPutForwardAgainWithAPrevoteQuorumAfterItsLockEvenIfLockedOnAnother() {
		final Block a = proposed(0, "a");
		final Block b = proposed(1, "b");
		final Proposal again = Proposal.signed(seed(3), CHAIN, 1, 2, 1, b);

		engine.start(1, validators, 0);
		lockOn(0, -1, a);

		assertThat(engine.lockedView(), equalTo(0));

		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 1, b), 20);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 1, b), 21);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 1, b), 22);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 2, null), 23);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 2, null), 24);

		assertThat(engine.view(), equalTo(2));

		final Ballot prevote = prevoteOf(engine.onMessage(again, 6000));

		assertThat(prevote.view(), equalTo(2));
		assertThat(prevote.blockHash(), equalTo(b.hash()));
	}

	@Example
	public void shouldPrevoteABlockPutForwardAgainWhenItsProofHoldsThePrevotesItNeverSaw() {
		final Block b = proposed(1, "b");

		inViewTwo();

		final Ballot prevote = prevoteOf(engine.onMessage(proposal(2, 1, b, prevotesOf(b, 1, 1, 2, 3)), 6000));

		assertThat(prevote.view(), equalTo(2));
		assertThat(prevote.blockHash(), equalTo(b.hash()));
		assertThat(engine.storedBallots(), equalTo(3));
	}

	@Example
	public void shouldPrevoteNilForABlockPutForwardAgainWithTooFewPrevotesInItsProof() {
		final Block b = proposed(1, "b");

		inViewTwo();

		prevotesNilOnlyWhenTheProposeTimerFires(proposal(2, 1, b, prevotesOf(b, 1, 1, 2)));
	}

	@Example
	public void shouldPrevoteNilForABlockPutForwardAgainWithAForgedPrevoteInItsProof() {
		final Block b = proposed(1, "b");

		inViewTwo();

		prevotesNilOnlyWhenTheProposeTimerFires(proposal(2, 1, b, withForgedThird(b)));
	}

	@Example
	public void shouldCountEachValidatorOnceInTheProofOfABlockPutForwardAgain() {
		final Block b = proposed(1, "b");
		final Ballot one = ballot(Ballot.Type.PREVOTE, 1, 1, b);

		inViewTwo();

		prevotesNilOnlyWhenTheProposeTimerFires(proposal(2, 1, b, List.of(one, one, prevoteBy(2, 1, b))));
	}

	@Example
	public void shouldNotCountAKeyOutsideTheSetInTheProofOfABlockPutForwardAgain() {
		final Block b = proposed(1, "b");
		final Ballot stranger = Ballot.signed(Ballot.Type.PREVOTE, seed(9), CHAIN, 1, 1, b.hash());

		inViewTwo();

		prevotesNilOnlyWhenTheProposeTimerFires(proposal(2, 1, b, List.of(prevoteBy(1, 1, b), prevoteBy(2, 1, b),
			stranger)));
	}

	@Example
	public void shouldIgnoreAProofOfMoreBallotsThanThereAreValidatorsEvenWhenItHoldsAQuorum() {
		final Block b = proposed(1, "b");

		inViewTwo();

		prevotesNilOnlyWhenTheProposeTimerFires(proposal(2, 1, b, List.of(prevoteBy(1, 1, b), prevoteBy(2, 1, b),
			prevoteBy(3, 1, b), prevoteBy(1, 1, b), prevoteBy(2, 1, b))));
	}

	@Example
	public void shouldPrevoteWhenTheQuorumOfAProofIsFollowedByBallotsThatAreNotRight() {
		final Block b = proposed(1, "b");

		inViewTwo();

		final List<Ballot> proof = List.of(prevoteBy(1, 1, b), prevoteBy(2, 1, b), prevoteBy(3, 1, b),
			forgedPrevote(4, 1, b));

		assertThat(prevoteOf(engine.onMessage(proposal(2, 1, b, proof), 6000)).blockHash(), equalTo(b.hash()));
	}

	@Example
	public void shouldPrevoteNilWhenFewerThanAQuorumOfAProofAreRightWhateverOrderTheyAreIn() {
		final Block b = proposed(1, "b");

		inViewTwo();

		prevotesNilOnlyWhenTheProposeTimerFires(proposal(2, 1, b, List.of(forgedPrevote(1, 4, b),
			forgedPrevote(2, 4, b), prevoteBy(3, 1, b), prevoteBy(4, 1, b))));
	}

	@Example
	public void shouldIgnoreACopyOfAReProposalWhoseProofWasStrippedOrChangedAndTakeTheRealOneAfterIt() {
		final Block b = proposed(1, "b");
		final List<Ballot> proof = prevotesOf(b, 1, 1, 2, 3);
		final Proposal real = proposal(2, 1, b, proof);
		final List<Ballot> trimmed = proof.subList(0, 2);
		final List<Ballot> reordered = List.of(proof.get(2), proof.get(1), proof.get(0));
		final List<Ballot> swapped = List.of(proof.get(0), proof.get(1), prevoteBy(4, 1, b));

		inViewTwo();

		for (final List<Ballot> changed : List.of(List.<Ballot>of(), trimmed, reordered, swapped)) {
			final Proposal copy = new Proposal(1, 2, 1, b, changed, real.sender(), real.signature());

			assertThat(engine.onMessage(copy, 6000).isEmpty(), is(true));
		}

		assertThat(engine.step(), equalTo(Consensus.Step.PROPOSE));
		assertThat(prevoteOf(engine.onMessage(real, 6001)).blockHash(), equalTo(b.hash()));
	}

	@Example
	public void shouldPrevoteNilForABlockWithAGoodProofWhenLockedOneViewAfterItsValidRound() {
		final Block b = proposed(0, "b");
		final Block c = proposed(1, "c");

		engine.start(1, validators, 0);
		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		endView(0);
		lockOn(1, -1, c);
		endView(1);

		assertThat(engine.view(), equalTo(2));
		assertThat(engine.lockedView(), equalTo(1));
		assertThat(prevoteOf(engine.onMessage(proposal(2, 0, b, prevotesOf(b, 0, 1, 2, 3)), 5001)).isNil(),
			is(true));
	}

	@Example
	public void shouldPrevoteABlockWithAGoodProofWhenLockedInExactlyItsValidRound() {
		final Block b = proposed(0, "b");
		final Block c = proposed(1, "c");

		engine.start(1, validators, 0);
		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		endView(0);
		lockOn(1, -1, c);
		endView(1);

		assertThat(engine.view(), equalTo(2));
		assertThat(engine.lockedView(), equalTo(1));
		assertThat(prevoteOf(engine.onMessage(proposal(2, 1, b, prevotesOf(b, 1, 1, 2, 3)), 5001)).blockHash(),
			equalTo(b.hash()));
	}

	@Example
	public void shouldNotLetAForgedBallotOfAValidatorShadowItsRealBallotInAProof() {
		final Block b = proposed(1, "b");
		final List<Ballot> proof = List.of(forgedPrevote(1, 4, b), prevoteBy(1, 1, b), prevoteBy(2, 1, b),
			prevoteBy(3, 1, b));

		inViewTwo();

		assertThat(prevoteOf(engine.onMessage(proposal(2, 1, b, proof), 6000)).blockHash(), equalTo(b.hash()));
	}

	@Example
	public void shouldPrevoteABlockPutForwardAgainWithAProofWhenLockedOnAnotherAtOrBeforeItsValidRound() {
		final Block a = proposed(0, "a");
		final Block b = proposed(1, "b");

		engine.start(1, validators, 0);
		lockOn(0, -1, a);
		endView(0);
		endView(1);

		assertThat(engine.view(), equalTo(2));
		assertThat(engine.lockedView(), equalTo(0));

		final Ballot prevote = prevoteOf(engine.onMessage(proposal(2, 1, b, prevotesOf(b, 1, 1, 2, 3)), 5001));

		assertThat(prevote.view(), equalTo(2));
		assertThat(prevote.blockHash(), equalTo(b.hash()));
	}

	@Example
	public void shouldCarryTheQuorumOfPrevotesItSawWhenItPutsABlockForwardAgain() {
		final Block a = proposed(0, "a");

		leader.start(1, validators, 0);
		leader.onMessage(proposal(0, -1, a), 10);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, a), 11);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, a), 12);
		leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, null), 13);
		leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, null), 14);
		leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 4, 0, null), 15);

		final Proposal again = proposalsSent(leader.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 5000)).get(0);

		assertThat(again.validRound(), equalTo(0));
		assertThat(again.polka().size(), greaterThanOrEqualTo(validators.quorum()));
		assertThat(again.polka().stream().map(Ballot::validator).distinct().count(),
			equalTo((long) again.polka().size()));
		assertThat(again.polka().stream().allMatch(vote -> vote.type() == Ballot.Type.PREVOTE && vote.view() == 0
			&& Arrays.equals(vote.blockHash(), a.hash()) && vote.hasValidSignature(CHAIN)), is(true));
		assertThat(again.hasValidSignature(CHAIN), is(true));
	}

	@Example
	public void shouldCarryNoProofForANewBlock() {
		leader.start(1, validators, 0);
		leader.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, null), 3001);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, null), 3002);
		leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, null), 3003);
		leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, null), 3004);

		final Proposal fresh = proposalsSent(leader.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 4005)).get(0);

		assertThat(fresh.validRound(), equalTo(-1));
		assertThat(fresh.polka().isEmpty(), is(true));
	}

	@Example
	public void shouldStillPrevoteABlockOnceItsRealQuorumArrivesAfterAProofThatDidNotHold() {
		final Block b = proposed(1, "b");

		inViewTwo();

		assertThat(ballots(engine.onMessage(proposal(2, 1, b, withForgedThird(b)), 6000)).isEmpty(), is(true));
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 1, b), 6001);
		assertThat(engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 1, b), 6002).isEmpty(), is(true));

		final Ballot prevote = prevoteOf(engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 1, b), 6003));

		assertThat(prevote.view(), equalTo(2));
		assertThat(prevote.blockHash(), equalTo(b.hash()));
	}

	@Example
	public void shouldTakeACertifiedBlockForItsHeightAndRefuseABadOne() {
		final Block block = proposed(0, "a");
		final Block certified = block.toBuilder().certificate(new Certificate(0, List.of(
			ballot(Ballot.Type.PRECOMMIT, 1, 0, block).endorsement(), ballot(Ballot.Type.PRECOMMIT, 2, 0, block)
				.endorsement(), ballot(Ballot.Type.PRECOMMIT, 3, 0, block).endorsement()))).build();
		final Block thin = block.toBuilder().certificate(new Certificate(0, List.of(
			ballot(Ballot.Type.PRECOMMIT, 1, 0, block).endorsement()))).build();

		engine.start(1, validators, 0);

		assertThat(decides(engine.onMessage(new Decision(thin), 5)), is(false));
		assertThat(decides(engine.onMessage(new Decision(certified), 6)), is(true));
		assertThat(engine.onMessage(new Decision(certified), 7).isEmpty(), is(true));
	}

	@Example
	public void shouldTakeACertifiedBlockWithoutRunningAView() {
		final Block block = proposed(0, "a");
		final Decision certified = new Decision(certified(block));

		app.goal = 0;
		engine.start(1, validators, 0);

		final List<Action> actions = engine.onMessage(certified, 5);

		assertThat(decidedBlock(actions).hash(), equalTo(block.hash()));
		assertThat(schedules(actions, Timeout.Kind.PROPOSE), is(false));
		assertThat(engine.outbox(), equalTo(List.of(certified)));
	}

	@Example
	public void shouldListItsOwnMessagesOfTheHeightForAResend() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);

		assertThat(engine.outbox().size(), equalTo(1));
		assertThat(engine.outbox().get(0) instanceof Ballot, is(true));
	}

	@Example
	public void shouldListItsOwnProposalAndBallotsOfTheHeightForAResend() {
		leader.start(1, validators, 0);
		leader.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, null), 3001);
		leader.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, null), 3002);
		leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, null), 3003);
		leader.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, null), 3004);

		final List<Message> before = leader.outbox();

		assertThat(before.stream().filter(m -> m instanceof Ballot).count(), equalTo(2L));

		leader.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 4005);

		final List<Message> after = leader.outbox();

		assertThat(after.stream().filter(m -> m instanceof Proposal).count(), equalTo(1L));
		assertThat(after.stream().filter(m -> m instanceof Ballot).count(), equalTo(3L));
	}

	@Example
	public void shouldScheduleTheTimerOfItsStepAgainWhenResumed() {
		engine.start(1, validators, 0);

		final List<Action> actions = engine.resume(50);

		assertThat(actions, equalTo(List.of(schedule(Timeout.Kind.PROPOSE, 0, 3000))));
	}

	@Example
	public void shouldScheduleThePrevoteAndPrecommitTimersAgainWhenResumed() {
		engine.start(1, validators, 0);
		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, proposed(0, "a")), 3001);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 2, 0, null), 3002);

		final List<Action> actions = engine.resume(50);

		assertThat(schedules(actions, Timeout.Kind.PREVOTE), is(true));
		assertThat(schedules(actions, Timeout.Kind.PROPOSE), is(false));
		assertThat(schedules(actions, Timeout.Kind.PRECOMMIT), is(false));

		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, null), 3003);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 0, null), 3004);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, null), 3005);

		assertThat(schedules(engine.resume(51), Timeout.Kind.PRECOMMIT), is(true));
	}

	@Example
	public void shouldKeepWhatItSaidAndNotVoteAgainWhenResumed() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);

		final List<Message> said = engine.outbox();
		final List<Action> actions = engine.resume(50);

		assertThat(said.size(), equalTo(1));
		assertThat(actions, equalTo(List.of(new Broadcast(said.get(0)))));
		assertThat(engine.outbox(), equalTo(said));
		assertThat(engine.storedBallots(), equalTo(1));
		assertThat(engine.step(), equalTo(Consensus.Step.PREVOTE));
		assertThat(engine.resume(51), equalTo(actions));
	}

	@Example
	public void shouldKeepTimeAndResumeWithoutAKey() {
		final Consensus observer = new Consensus(app, CHAIN, null, TIMEOUTS);

		observer.start(1, validators, 0);

		assertThat(observer.resume(5), equalTo(List.of(schedule(Timeout.Kind.PROPOSE, 0, 3000))));
		assertThat(observer.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000).isEmpty(), is(true));
		assertThat(observer.step(), equalTo(Consensus.Step.PREVOTE));
		assertThat(observer.resume(3001).isEmpty(), is(true));
		assertThat(observer.outbox().isEmpty(), is(true));
	}

	@Example
	public void shouldDecideOnACertifiedBlockWithoutAKeyAndHaveNothingToResume() {
		final Consensus observer = new Consensus(app, CHAIN, null, TIMEOUTS);
		final Block block = proposed(0, "a");

		observer.start(1, validators, 0);

		final List<Action> actions = observer.onMessage(new Decision(certified(block)), 5);

		assertThat(decidedBlock(actions).hash(), equalTo(block.hash()));
		assertThat(observer.resume(6).isEmpty(), is(true));
	}

	/* The proposal never came, and more than 2/3 of the precommits are in, none for a block: only the timeout is
	   left, which is the moment the node decides whether to go on */
	private List<Action> quorumOfNoBlock(int view, long at) {
		return quorumOfNoBlock(engine, view, at);
	}

	private static List<Action> quorumOfNoBlock(Consensus node, int view, long at) {
		final List<Action> actions = new ArrayList<>(node.onTimeout(timeout(Timeout.Kind.PROPOSE, view), at));

		for (int validator = 1; validator <= 3; validator++) {
			actions.addAll(node.onMessage(ballot(Ballot.Type.PRECOMMIT, validator, view, null), at + 1));
		}

		return actions;
	}

	/* A node without work that two validators, more than a third, have started, in the view that ends with nothing.
	   The ballots of one view are a prevote and a precommit, so that its prevotes make no quorum. */
	private void startQuietNodeInViewZero() {
		app.goal = 0;
		engine.start(1, validators, 0);
		wakeInView(0, 5);
	}

	private List<Action> wakeInView(int view, long at) {
		final List<Action> actions = new ArrayList<>(engine.onMessage(prevoteBy(1, view, null), at));

		actions.addAll(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, view, null), at));
		return actions;
	}

	private void restAgainIn(int view, long at) {
		quorumOfNoBlock(view, at);
		engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, view), at + 2);
	}

	private void goIdleAfterViewZero() {
		startQuietNodeInViewZero();
		quorumOfNoBlock(0, 3005);
		engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007);
	}

	@Example
	public void shouldGoIdleWhenTheViewEndsWithNoWorkNoBlockAndNoProposal() {
		startQuietNodeInViewZero();

		assertThat(engine.running(), is(true));

		quorumOfNoBlock(0, 3005);

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
		assertThat(engine.view(), equalTo(0));
		assertThat(engine.nextView(), equalTo(1));
	}

	@Example
	public void shouldKeepTheNextViewAboveTheViewItSignsInFromTheFirstViewOn() {
		app.goal = 0;
		engine.start(1, validators, 0);

		assertThat(engine.nextView(), equalTo(0));

		wakeInView(0, 5);

		assertThat(engine.nextView(), equalTo(1));
	}

	@Example
	public void shouldKeepTheNextViewAboveTheViewItSkippedTo() {
		engine.start(1, validators, 0);
		engine.onMessage(prevoteBy(1, 5, null), 5);
		engine.onMessage(prevoteBy(2, 5, null), 6);

		assertThat(engine.view(), equalTo(5));
		assertThat(engine.nextView(), equalTo(6));
	}

	@Example
	public void shouldKeepTheNextViewAtTheLastViewWhenItSkipsToIt() {
		engine.start(1, validators, 0);
		engine.onMessage(prevoteBy(1, Integer.MAX_VALUE, null), 5);
		engine.onMessage(prevoteBy(2, Integer.MAX_VALUE, null), 6);

		assertThat(engine.view(), equalTo(Integer.MAX_VALUE));
		assertThat(engine.nextView(), equalTo(Integer.MAX_VALUE));
	}

	@Example
	public void shouldStartTheViewAfterTheOnesItSignedInWhenMoreThanOneThirdInItWakeAnIdleNode() {
		goIdleAfterViewZero();

		assertThat(engine.onMessage(prevoteBy(1, 1, null), 6000).isEmpty(), is(true));
		assertThat(engine.running(), is(false));

		final List<Action> woken = engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 1, null), 6000);

		assertThat(woken.get(0), equalTo(schedule(Timeout.Kind.PROPOSE, 1, 3500)));
		assertThat(engine.running(), is(true));
		assertThat(engine.view(), equalTo(1));

		final List<Ballot> signed = ballots(engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 1), 9500));

		assertThat(signed.size(), equalTo(1));
		assertThat(signed.get(0).view(), equalTo(1));
	}

	/* A peer that is still in the view the node rested in is no further on than it is, so it starts no view, but it may
	   have lost what the node signed there while it was away, long after the node stopped saying it: a new ballot of
	   such a peer opens the window of saying it again, and a copy does not. The node rested at 3007 ms and its window
	   closed at 8007 ms. */
	@Example
	public void shouldSayAgainWhatItSignedButNotWakeForANewLateBallotOfTheViewItRestedIn() {
		goIdleAfterViewZero();

		assertThat(engine.resume(20_000).isEmpty(), is(true));
		assertThat(engine.onMessage(prevoteBy(2, 0, null), 20_000).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
		assertThat(engine.resume(20_001), equalTo(List.of(new Broadcast(engine.outbox().get(0)))));
		assertThat(engine.resume(20_000 + 5_000).size(), equalTo(1));
		assertThat(engine.resume(20_000 + 5_001).isEmpty(), is(true));
		assertThat(engine.onMessage(prevoteBy(2, 0, null), 30_000).isEmpty(), is(true));
		assertThat(engine.resume(30_001).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	/* One validator alone ahead wakes a node at rest, as a validator that holds a transaction the others lack has to
	   pull them on to its turn to propose, but no more than once in half a minute: one that lies can make the others
	   run a view that often and no more, whatever it signs meanwhile. Validator 1 woke the node at 5 ms. A ballot that
	   came too early is not lost: the tick of the driver, which comes with onWork(), wakes the node once it may. */
	@Example
	public void shouldWakeAnIdleNodeForOneValidatorAloneAtMostOnceInHalfAMinute() {
		goIdleAfterViewZero();

		for (int view = 1; view <= Consensus.MAX_VIEW_AHEAD + 3; view++) {
			assertThat(engine.onMessage(prevoteBy(1, view, null), 6000 + view).isEmpty(), is(true));
			assertThat(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, view, null), 6000 + view).isEmpty(),
				is(true));
		}

		assertThat(engine.onWork(5 + Consensus.LONE_WAKE_MILLIS - 1).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
		assertThat(engine.onWork(5 + Consensus.LONE_WAKE_MILLIS), equalTo(List.of(schedule(Timeout.Kind.PROPOSE, 1,
			3500))));
		assertThat(engine.view(), equalTo(1));
	}

	/* The half minute is the node's, not each validator's, or every liar more would make the others run views more
	   often. Validator 1 woke the node at 5 ms. */
	@Example
	public void shouldNotWakeAnIdleNodeForAnotherValidatorAloneWithinHalfAMinuteOfTheLastSuchWake() {
		goIdleAfterViewZero();

		assertThat(engine.onMessage(prevoteBy(3, 1, null), 6000).isEmpty(), is(true));
		assertThat(engine.onWork(5 + Consensus.LONE_WAKE_MILLIS - 1).isEmpty(), is(true));
		assertThat(engine.onWork(5 + Consensus.LONE_WAKE_MILLIS), equalTo(List.of(schedule(Timeout.Kind.PROPOSE, 1,
			3500))));
	}

	@Example
	public void shouldStartAHeightOnTheBallotOfOneValidator() {
		app.goal = 0;
		engine.start(1, validators, 0);

		assertThat(engine.onMessage(prevoteBy(1, 0, null), 5), equalTo(List.of(schedule(Timeout.Kind.PROPOSE, 0,
			3000))));
		assertThat(engine.running(), is(true));
	}

	@Example
	public void shouldNotWakeAnIdleNodeForAProposalOfAValidatorAloneBeyondTheNextViewOnATick() {
		goIdleAfterViewZero();
		engine.onMessage(proposal(2, -1, proposed(2, "c")), 6000);

		assertThat(engine.onWork(6000 + Consensus.LONE_WAKE_MILLIS).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	/* The proposer of the next view, who has work, is ahead of the node, which is how an honest peer wakes the chain */
	@Example
	public void shouldWakeAnIdleNodeForTheProposalOfTheNextView() {
		final Block next = proposed(1, "b");

		goIdleAfterViewZero();

		final List<Action> woken = engine.onMessage(proposal(1, -1, next), 6000);

		assertThat(engine.running(), is(true));
		assertThat(engine.view(), equalTo(1));
		assertThat(prevoteOf(woken).blockHash(), equalTo(next.hash()));
	}

	/* The proposer of a view further on may sign a proposal for it at any time, but nobody else need be in that view,
	   so waking for it would only run a view for nothing. Validator 3 proposes the views 2 and 42. */
	@Example
	public void shouldNotWakeAnIdleNodeForAProposalBeyondTheNextView() {
		goIdleAfterViewZero();

		assertThat(engine.onMessage(proposal(2, -1, proposed(2, "c")), 6000).isEmpty(), is(true));
		assertThat(engine.onMessage(proposal(42, -1, proposed(42, "far")), 6001).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	/* The peers that a proposal further on is for are in its view, and once one more of them than the proposer is
	   heard there the node wakes, goes to that view and answers the proposal it holds. Validator 2 proposes view 5. */
	@Example
	public void shouldAnswerAProposalBeyondTheNextViewOnceAnotherValidatorIsHeardInItsView() {
		final Block ahead = proposed(5, "e");

		goIdleAfterViewZero();

		assertThat(engine.onMessage(proposal(5, -1, ahead), 6000).isEmpty(), is(true));

		final List<Action> woken = engine.onMessage(prevoteBy(3, 5, ahead), 6001);

		assertThat(engine.view(), equalTo(5));
		assertThat(prevoteOf(woken).blockHash(), equalTo(ahead.hash()));
	}

	@Example
	public void shouldNotWakeAnIdleNodeWithAMessageItAlreadyHas() {
		goIdleAfterViewZero();

		assertThat(engine.onMessage(prevoteBy(1, 0, null), 6000).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	@Example
	public void shouldStartTheViewAfterTheOnesItSignedInWhenWorkTurnsUpForAnIdleNode() {
		goIdleAfterViewZero();
		app.goal = 3;

		assertThat(engine.onWork(6000), equalTo(List.of(schedule(Timeout.Kind.PROPOSE, 1, 3500))));
		assertThat(engine.view(), equalTo(1));
		assertThat(engine.onWork(6001).isEmpty(), is(true));
	}

	@Example
	public void shouldIgnoreTheTimersOfTheViewAnIdleNodeLeftBehind() {
		goIdleAfterViewZero();

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PREVOTE, 0), 4000).isEmpty(), is(true));
		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 4001).isEmpty(), is(true));
		assertThat(engine.resume(4002).stream().noneMatch(action -> action instanceof Schedule), is(true));
		assertThat(engine.running(), is(false));
	}

	/* The node rested at 3007 in view 0, where three precommit timeouts are 4500 ms, which is less than the shortest
	   window, so it sends again until 3007 + 5000 */
	@Example
	public void shouldSendWhatItSignedAgainWhileItRestsAndThenGoSilent() {
		goIdleAfterViewZero();

		final List<Message> signed = engine.outbox();
		final List<Action> again = engine.resume(4002);

		assertThat(signed.size(), equalTo(1));
		assertThat(again, equalTo(List.of(new Broadcast(signed.get(0)))));
		assertThat(engine.resume(3007 + 5000), equalTo(again));
		assertThat(engine.resume(3007 + 5001).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	@Example
	public void shouldSendAgainForAtLeastFiveSecondsHoweverShortThePrecommitTimeoutIs() {
		final Consensus quick = new Consensus(app, CHAIN, seed(4), new Timeouts(3000, 500, 1000, 300, 10, 1));

		app.goal = 0;
		quick.start(1, validators, 0);
		quick.onMessage(prevoteBy(1, 0, null), 5);
		quick.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 0, null), 5);
		quorumOfNoBlock(quick, 0, 3005);
		quick.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007);

		assertThat(quick.running(), is(false));
		assertThat(quick.resume(3007 + 5000).size(), equalTo(1));
		assertThat(quick.resume(3007 + 5001).isEmpty(), is(true));
	}

	@Example
	public void shouldSendAgainForAtMostAMinuteHoweverLongThePrecommitTimeoutOfTheViewIs() {
		final int far = 1000;

		app.goal = 0;
		engine.start(1, validators, 0);
		engine.onMessage(prevoteBy(1, far, null), 5);
		engine.onMessage(prevoteBy(2, far, null), 6);

		assertThat(engine.view(), equalTo(far));

		quorumOfNoBlock(engine, far, 3005);
		engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, far), 3007);

		assertThat(engine.running(), is(false));
		assertThat(engine.resume(3007 + 60_000).size(), equalTo(1));
		assertThat(engine.resume(3007 + 60_001).isEmpty(), is(true));
	}

	@Example
	public void shouldSendAgainFromTheTimeItRestsAndForAsLongAsTheViewItRestedInWaits() {
		goIdleAfterViewZero();
		wakeInView(1, 6000);
		restAgainIn(1, 9500);

		assertThat(engine.running(), is(false));
		assertThat(engine.resume(9600).size(), equalTo(2));
		assertThat(engine.resume(9502 + 3 * 2200).size(), equalTo(2));
		assertThat(engine.resume(9502 + 3 * 2200 + 1).isEmpty(), is(true));
	}

	/* The proposer of the next view, who has work, is ahead of this node and its proposal is already here: resting
	   now would leave the proposal unanswered, as nothing new would ever wake the node for it */
	@Example
	public void shouldNotRestWhenTheProposalOfALaterViewIsAlreadyHeld() {
		final Block next = proposed(1, "b");

		startQuietNodeInViewZero();
		engine.onMessage(proposal(1, -1, next), 10);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, null), 11);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 0, null), 12);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, null), 13);

		final List<Action> actions = engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 400);

		assertThat(engine.running(), is(true));
		assertThat(engine.view(), equalTo(1));
		assertThat(prevoteOf(actions).blockHash(), equalTo(next.hash()));
		assertThat(prevoteOf(actions).view(), equalTo(1));
	}

	/* One validator in the next view is a single voice, which would not wake the node either: it takes more than a
	   third of the validators, and the node skips to their view as soon as they are in it */
	@Example
	public void shouldRestWhenOnlyOneValidatorHasAPrevoteOfTheNextView() {
		startQuietNodeInViewZero();
		engine.onMessage(prevoteBy(2, 1, null), 10);
		quorumOfNoBlock(0, 3005);

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	@Example
	public void shouldRestWhenOnlyOneValidatorHasAPrecommitOfTheNextView() {
		startQuietNodeInViewZero();
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 1, null), 10);
		quorumOfNoBlock(0, 3005);
		engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007);

		assertThat(engine.running(), is(false));
		assertThat(engine.view(), equalTo(0));
	}

	@Example
	public void shouldRestWhenOnlyABallotTwentyViewsAheadIsHeld() {
		startQuietNodeInViewZero();
		engine.onMessage(prevoteBy(2, 20, null), 10);
		quorumOfNoBlock(0, 3005);

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	/* The proposer of a view that far ahead can sign a proposal for it that is kept, as the view is within the window,
	   but one proposal must not be able to keep the whole chain awake until its view comes: a peer that is really ahead
	   has signed ballots in the next view too */
	@Example
	public void shouldRestWhenOnlyTheProposalOfTheViewAfterTheNextIsHeld() {
		startQuietNodeInViewZero();
		engine.onMessage(proposal(2, -1, proposed(2, "c")), 10);
		quorumOfNoBlock(0, 3005);

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	@Example
	public void shouldRestWhenOnlyTheProposalTwentyViewsAheadIsHeld() {
		startQuietNodeInViewZero();
		engine.onMessage(proposal(20, -1, proposed(20, "d")), 10);
		quorumOfNoBlock(0, 3005);

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	@Example
	public void shouldRestWhenTheNearestBallotHeldIsOfTheViewAfterTheNext() {
		startQuietNodeInViewZero();
		engine.onMessage(prevoteBy(2, 2, null), 10);
		quorumOfNoBlock(0, 3005);

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	@Example
	public void shouldRestWhenTheProposalOfALaterViewIsNotFromTheProposerOfThatView() {
		final Proposal fromAnother = Proposal.signed(seed(3), CHAIN, 1, 1, -1, proposed(1, "b"));

		startQuietNodeInViewZero();

		assertThat(engine.onMessage(fromAnother, 10).isEmpty(), is(true));

		quorumOfNoBlock(0, 3005);

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	/* Ballots beyond the window are checked and noted but not kept, so anyone could play the same ones back again and
	   again; once the node has been in their view they are behind it and wake it no more */
	@Example
	public void shouldWakeARestingNodeOnceForTheSameBallotsFarAhead() {
		final Ballot farOne = prevoteBy(1, 500, null);
		final Ballot farTwo = prevoteBy(2, 500, null);

		goIdleAfterViewZero();

		assertThat(engine.onMessage(farOne, 6000).isEmpty(), is(true));
		assertThat(schedules(engine.onMessage(farTwo, 6001), Timeout.Kind.PROPOSE), is(true));
		assertThat(engine.view(), equalTo(500));

		restAgainIn(500, 9500);

		assertThat(engine.running(), is(false));
		assertThat(engine.onMessage(farOne, 20_000).isEmpty(), is(true));
		assertThat(engine.onMessage(farTwo, 20_001).isEmpty(), is(true));
		assertThat(engine.running(), is(false));
	}

	@Example
	public void shouldWakeARestingNodeAgainOnlyWhenMoreThanOneThirdAreBeyondTheViewItRestedIn() {
		goIdleAfterViewZero();
		engine.onMessage(prevoteBy(1, 500, null), 6000);
		engine.onMessage(prevoteBy(2, 500, null), 6001);
		restAgainIn(500, 9500);

		assertThat(engine.onMessage(prevoteBy(1, 600, null), 20_000).isEmpty(), is(true));
		assertThat(engine.onMessage(prevoteBy(3, 500, null), 20_001).isEmpty(), is(true));
		assertThat(schedules(engine.onMessage(prevoteBy(2, 501, null), 20_002), Timeout.Kind.PROPOSE), is(true));
		assertThat(engine.view(), equalTo(501));
	}

	@Example
	public void shouldNotSendAgainAfterADecision() {
		goIdleAfterViewZero();
		engine.onMessage(new Decision(certified(proposed(0, "a"))), 5000);

		assertThat(engine.resume(5001).isEmpty(), is(true));
	}

	@Example
	public void shouldHaveNothingToSendWhileItRestsWithoutAKey() {
		final Consensus observer = new Consensus(app, CHAIN, null, TIMEOUTS);

		app.goal = 0;
		observer.start(1, validators, 0);
		observer.onMessage(prevoteBy(1, 0, null), 5);

		for (int validator = 1; validator <= 3; validator++) {
			observer.onMessage(ballot(Ballot.Type.PRECOMMIT, validator, 0, null), 6);
		}

		observer.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007);

		assertThat(observer.running(), is(false));
		assertThat(observer.resume(3008).isEmpty(), is(true));
	}

	private static int viewOf(Message message) {
		return switch (message) {
			case Proposal proposal -> proposal.view();
			case Ballot ballot -> ballot.view();
			case Decision decision -> decision.block().getView();
		};
	}

	private List<Message> saidAgain(long at) {
		return engine.resume(at).stream().filter(Broadcast.class::isInstance)
			.map(action -> ((Broadcast) action).message()).toList();
	}

	/* A height that a liar keeps waking runs view after view, and a node that said all it signed in them on every tick
	   would say more than a peer takes. What it signed in its view and the one before is said every time, and of the
	   rest a few at a time in turn, as an old ballot may still be wanted, so each comes round. */
	@Example
	public void shouldSayAgainItsLastTwoViewsEveryTimeAndTheRestAFewAtATimeInTurn() {
		engine.start(1, validators, 0);

		for (int view = 0; view < 10; view++) {
			quorumOfNoBlock(view, 4000L * (view + 1));
			engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, view), 4000L * (view + 1) + 2);
		}

		final List<Message> own = engine.outbox();
		final List<Message> recent = own.stream().filter(message -> viewOf(message) >= 9).toList();
		final Set<Message> heard = new HashSet<>();

		for (int turn = 0; turn * Consensus.OLDER_PER_RESEND < own.size(); turn++) {
			final List<Message> again = saidAgain(50_000 + turn);

			assertThat(again.containsAll(recent), is(true));
			assertThat(again.size(), equalTo(recent.size() + Consensus.OLDER_PER_RESEND));
			heard.addAll(again);
		}

		assertThat(engine.view(), equalTo(10));
		assertThat(own.size(), greaterThan(recent.size() + Consensus.OLDER_PER_RESEND));
		assertThat(heard, equalTo(Set.copyOf(own)));
	}

	@Example
	public void shouldKeepRunningIntoTheNextViewWhenThereIsWork() {
		engine.start(1, validators, 0);
		quorumOfNoBlock(0, 3000);

		assertThat(schedules(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3002), Timeout.Kind.PROPOSE),
			is(true));
		assertThat(engine.running(), is(true));
		assertThat(engine.view(), equalTo(1));
	}

	@Example
	public void shouldKeepRunningIntoTheNextViewWhenItSawTheProposalOfTheView() {
		startQuietNodeInViewZero();
		engine.onMessage(proposal(0, -1, proposed(0, "a")), 10);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, null), 11);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 0, null), 12);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, null), 13);

		assertThat(schedules(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 400), Timeout.Kind.PROPOSE),
			is(true));
		assertThat(engine.running(), is(true));
		assertThat(engine.view(), equalTo(1));
	}

	@Example
	public void shouldKeepRunningIntoTheNextViewWhenItHoldsALock() {
		final Block a = proposed(0, "a");

		app.goal = 0;
		engine.start(1, validators, 0);
		lockOn(0, -1, a);
		endView(0);
		endView(1);

		assertThat(engine.lockedView(), equalTo(0));
		assertThat(engine.running(), is(true));
		assertThat(engine.view(), equalTo(2));
	}

	/* The block got more than 2/3 of the prevotes in the view, but only after the node had given up on the view, so
	   it is the one to put forward again and not the one the node is locked on */
	@Example
	public void shouldKeepRunningIntoTheNextViewWhenItHoldsAValidBlockAndNoLock() {
		final Block a = proposed(0, "a");

		app.goal = 0;
		engine.start(1, validators, 0);
		engine.onMessage(prevoteBy(1, 0, a), 10);
		engine.onMessage(prevoteBy(2, 0, a), 11);
		engine.onMessage(prevoteBy(3, 0, a), 12);
		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		engine.onTimeout(timeout(Timeout.Kind.PREVOTE, 0), 4000);
		engine.onMessage(proposal(0, -1, a), 4001);

		assertThat(engine.lockedView(), equalTo(-1));

		endView(0);
		endView(1);

		assertThat(engine.running(), is(true));
		assertThat(engine.view(), equalTo(2));
	}

	@Example
	public void shouldNeverSignTwoBallotsOfOneTypeAndViewWhenItGoesIdleAndWakesAgain() {
		final List<Action> run = new ArrayList<>();

		app.goal = 0;
		run.addAll(engine.start(1, validators, 0));
		run.addAll(wakeInView(0, 5));
		run.addAll(quorumOfNoBlock(0, 3005));
		run.addAll(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007));
		run.addAll(wakeInView(1, 6000));
		run.addAll(quorumOfNoBlock(engine.view(), 9500));
		run.addAll(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, engine.view()), 9502));

		final List<Ballot> signed = ballots(run);

		assertThat(signed.size(), equalTo(2));
		assertThat(signed.stream().map(vote -> vote.type() + " " + vote.view()).distinct().count(),
			equalTo((long) signed.size()));
		assertThat(engine.running(), is(false));
	}

	@Example
	public void shouldNotGoIdleInTheLastViewAsThereIsNoViewAfterIt() {
		final int last = Integer.MAX_VALUE;

		app.goal = 0;
		engine.start(1, validators, 0);
		engine.onMessage(prevoteBy(1, last, null), 5);
		engine.onMessage(prevoteBy(2, last, null), 6);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, last, null), 7);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, last, null), 8);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, last, null), 9);

		assertThat(engine.view(), equalTo(last));
		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, last), 10).isEmpty(), is(true));
		assertThat(engine.running(), is(true));
		assertThat(engine.view(), equalTo(last));
	}

	/* The quorum is of a proposal of an earlier view, so the view it ends in has no proposal of its own, and no work,
	   lock or valid block either: only the refusal still binds the node to the block */
	@Example
	public void shouldKeepRunningWhileAQuorumItRefusedToDecideOnIsPending() {
		final Block block = proposed(0, "a");

		app.goal = 0;
		app.refuseCertified = true;
		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, block), 11);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 0, block), 12);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, block), 13);
		engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 20);

		assertThat(engine.view(), equalTo(1));

		for (int validator = 1; validator <= 3; validator++) {
			engine.onMessage(ballot(Ballot.Type.PRECOMMIT, validator, 1, null), 21);
		}

		assertThat(schedules(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 1), 30), Timeout.Kind.PROPOSE),
			is(true));
		assertThat(engine.running(), is(true));
		assertThat(engine.view(), equalTo(2));

		app.refuseCertified = false;

		assertThat(decidedBlock(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 2), 40)).hash(),
			equalTo(block.hash()));
	}

	@Example
	public void shouldWakeInTheLastViewWhenItWentIdleInTheOneBeforeIt() {
		final int before = Integer.MAX_VALUE - 1;

		app.goal = 0;
		engine.start(1, validators, 0);
		engine.onMessage(prevoteBy(1, before, null), 5);
		engine.onMessage(prevoteBy(2, before, null), 6);
		quorumOfNoBlock(before, 7);

		assertThat(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, before), 10).isEmpty(), is(true));
		assertThat(engine.running(), is(false));

		wakeInView(Integer.MAX_VALUE, 11);

		assertThat(engine.view(), equalTo(Integer.MAX_VALUE));
		assertThat(engine.running(), is(true));
	}

	@Example
	public void shouldJudgeARefusedBlockAgainInTheNextViewButNotWithinTheViewItWasRefusedIn() {
		final Block b = proposed(0, "b");

		app.refuse = true;
		engine.start(1, validators, 0);

		assertThat(prevoteOf(engine.onMessage(proposal(0, -1, b), 10)).isNil(), is(true));
		assertThat(app.validated(b), equalTo(1));

		engine.onMessage(prevoteBy(1, 0, b), 11);
		engine.onMessage(prevoteBy(2, 0, b), 12);
		engine.onMessage(prevoteBy(3, 0, b), 13);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, null), 14);

		assertThat(app.validated(b), equalTo(1));
		assertThat(engine.lockedView(), equalTo(-1));

		endView(0);
		app.refuse = false;

		final Ballot prevote = prevoteOf(engine.onMessage(proposal(1, 0, b, prevotesOf(b, 0, 1, 2, 3)), 5001));

		assertThat(engine.view(), equalTo(1));
		assertThat(prevote.view(), equalTo(1));
		assertThat(prevote.blockHash(), equalTo(b.hash()));
	}

	@Example
	public void shouldAskOnlyOnceAboutABlockTheApplicationTookAcrossTheViews() {
		final Block a = proposed(0, "a");

		engine.start(1, validators, 0);
		lockOn(0, -1, a);
		endView(0);
		engine.onMessage(proposal(1, 0, a), 5001);

		assertThat(app.validated(a), equalTo(1));
	}

	@Example
	public void shouldDecideOnlyWhenTheApplicationTakesTheCertifiedBlockAndAskAgainInTheNextView() {
		final Block block = proposed(0, "a");

		app.refuseCertified = true;
		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, block), 11);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 0, block), 12);

		assertThat(decides(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, block), 13)), is(false));
		assertThat(app.certifiedCalls, equalTo(1));
		assertThat(decides(engine.onMessage(prevoteBy(1, 0, block), 14)), is(false));
		assertThat(app.certifiedCalls, equalTo(1));

		app.refuseCertified = false;

		final Block decided = decidedBlock(engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 20));

		assertThat(decided.hash(), equalTo(block.hash()));
		assertThat(decided.getCertificate().view(), equalTo(0));
		assertThat(app.certifiedCalls, equalTo(2));
	}

	/* Asking is no event: whatever the answer is, the engine is left exactly as it was */
	private boolean asked(Message message) {
		final SignState before = engine.snapshot();
		final int stored = engine.storedBallots();
		final List<BallotBox.Equivocation> evidence = engine.evidence();
		final boolean held = engine.holds(message);

		assertThat(engine.holds(message), equalTo(held));
		assertThat(engine.snapshot(), equalTo(before));
		assertThat(engine.storedBallots(), equalTo(stored));
		assertThat(engine.evidence(), equalTo(evidence));
		return held;
	}

	@Example
	public void shouldHoldABallotOnceItIsStoredAndNotBefore() {
		final Block block = proposed(0, "a");
		final Ballot prevote = prevoteBy(1, 0, block);

		engine.start(1, validators, 0);

		assertThat(asked(prevote), is(false));

		engine.onMessage(prevote, 10);

		assertThat(asked(prevote), is(true));
		assertThat(asked(ballot(Ballot.Type.PRECOMMIT, 1, 0, block)), is(false));
		assertThat(asked(prevoteBy(2, 0, block)), is(false));
		assertThat(asked(prevoteBy(1, 1, block)), is(false));
	}

	@Example
	public void shouldNotHoldASecondDifferentBallotOfAHeldSlot() {
		final Ballot first = prevoteBy(1, 0, proposed(0, "a"));
		final Ballot second = prevoteBy(1, 0, proposed(0, "b"));
		final Ballot nil = prevoteBy(1, 0, null);

		engine.start(1, validators, 0);
		engine.onMessage(first, 10);

		assertThat(asked(second), is(false));
		assertThat(asked(nil), is(false));

		engine.onMessage(second, 11);

		assertThat(engine.evidence().size(), equalTo(1));
		assertThat(asked(second), is(false));
		assertThat(asked(first), is(true));
	}

	@Example
	public void shouldNotHoldABallotWithABadSignature() {
		final Block block = proposed(0, "a");
		final Ballot held = prevoteBy(1, 1, block);
		final Ballot copy = forgedPrevote(1, 2, block);
		final Ballot forged = forgedPrevote(3, 4, block);

		engine.start(1, validators, 0);
		engine.onMessage(held, 10);
		engine.onMessage(copy, 11);
		engine.onMessage(forged, 12);

		assertThat(asked(held), is(true));
		assertThat(asked(copy), is(false));
		assertThat(asked(forged), is(false));
	}

	@Example
	public void shouldHoldABallotOnlyUpToTheEdgeOfTheWindow() {
		final Block block = proposed(0, "a");
		final Ballot inside = prevoteBy(1, Consensus.MAX_VIEW_AHEAD, block);
		final Ballot outside = prevoteBy(1, Consensus.MAX_VIEW_AHEAD + 1, block);

		engine.start(1, validators, 0);
		engine.onMessage(inside, 10);
		engine.onMessage(outside, 11);

		assertThat(asked(inside), is(true));
		assertThat(asked(outside), is(false));
	}

	@Example
	public void shouldLeaveABallotBeyondTheWindowNewWhenAskedAboutIt() {
		final Ballot far = prevoteBy(1, Consensus.MAX_VIEW_AHEAD + 8, proposed(0, "a"));

		app.goal = 0;
		engine.start(1, validators, 0);
		engine.onMessage(prevoteBy(2, Consensus.MAX_VIEW_AHEAD + 8, null), 9);

		assertThat(asked(far), is(false));
		assertThat(engine.onMessage(far, 10).isEmpty(), is(false));
		assertThat(asked(far), is(false));
		assertThat(engine.onMessage(far, 11).isEmpty(), is(true));
	}

	@Example
	public void shouldHoldTheFirstProposalOfAViewOnceItIsStored() {
		final Proposal first = proposal(0, -1, proposed(0, "a"));
		final Proposal second = proposal(0, -1, proposed(0, "b"));

		engine.start(1, validators, 0);

		assertThat(asked(first), is(false));

		engine.onMessage(first, 10);

		assertThat(asked(first), is(true));
		assertThat(asked(second), is(false));

		engine.onMessage(second, 11);

		assertThat(asked(second), is(false));
		assertThat(asked(first), is(true));
	}

	@Example
	public void shouldNotHoldAProposalThatIsForgedOrBeyondTheWindow() {
		final int edge = Consensus.MAX_VIEW_AHEAD;
		final Proposal forged = forgedProposal(1);
		final Proposal inside = proposal(edge, -1, proposed(edge, "inside"));
		final Proposal outside = proposal(edge + 1, -1, proposed(edge + 1, "outside"));

		engine.start(1, validators, 0);
		engine.onMessage(forged, 10);
		engine.onMessage(inside, 11);
		engine.onMessage(outside, 12);

		assertThat(asked(forged), is(false));
		assertThat(asked(inside), is(true));
		assertThat(asked(outside), is(false));
	}

	@Example
	public void shouldLeaveAProposalNewWhenAskedAboutIt() {
		final Proposal proposal = proposal(0, -1, proposed(0, "a"));

		engine.start(1, validators, 0);

		assertThat(asked(proposal), is(false));
		assertThat(prevoteOf(engine.onMessage(proposal, 10)).isNil(), is(false));
		assertThat(asked(proposal), is(true));
	}

	@Example
	public void shouldHoldNothingOfAnotherHeight() {
		final Block next = app.block(2, 0, validators.proposerAt(2, 0), 100, "next");
		final Ballot ballot = Ballot.signed(Ballot.Type.PREVOTE, seed(1), CHAIN, 2, 0, next.hash());
		final Proposal proposal = Proposal.signed(seed(1), CHAIN, 2, 0, -1, next);
		final Decision decision = new Decision(next);

		engine.start(1, validators, 0);
		engine.onMessage(ballot, 10);
		engine.onMessage(proposal, 11);
		engine.onMessage(decision, 12);

		assertThat(asked(ballot), is(false));
		assertThat(asked(proposal), is(false));
		assertThat(asked(decision), is(false));
	}

	@Example
	public void shouldHoldNothingBeforeItHasStarted() {
		final Block block = proposed(0, "a");

		assertThat(asked(prevoteBy(1, 0, block)), is(false));
		assertThat(asked(proposal(0, -1, block)), is(false));
		assertThat(asked(new Decision(certified(block))), is(false));
	}

	@Example
	public void shouldHoldADecisionOnceTheEngineHasDecidedOnThatBlock() {
		final Block block = proposed(0, "a");
		final Decision decision = new Decision(certified(block));
		final Decision otherQuorum = new Decision(block.endorsedBy(seed(2), CHAIN).endorsedBy(seed(3), CHAIN)
			.endorsedBy(seed(4), CHAIN));
		final Decision otherBlock = new Decision(certified(proposed(0, "b")));

		engine.start(1, validators, 0);

		assertThat(asked(decision), is(false));

		engine.onMessage(decision, 10);

		assertThat(asked(decision), is(true));
		assertThat(asked(otherQuorum), is(true));
		assertThat(asked(otherBlock), is(false));
		assertThat(asked(new Decision(app.block(2, 0, key(1), 100, "next"))), is(false));
	}

	@Example
	public void shouldHoldTheDecisionTheEngineReachedByItself() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, block), 11);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 2, 0, block), 12);

		final Block decided = decidedBlock(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, block), 13));

		assertThat(asked(new Decision(decided)), is(true));
		assertThat(asked(new Decision(certified(proposed(0, "b")))), is(false));
	}

	@Example
	public void shouldHoldNothingOfAHeightOnceTheNextOneHasStarted() {
		final Block block = proposed(0, "a");
		final Proposal proposal = proposal(0, -1, block);
		final Ballot prevote = prevoteBy(1, 0, block);
		final Decision decision = new Decision(certified(block));

		engine.start(1, validators, 0);
		engine.onMessage(proposal, 10);
		engine.onMessage(prevote, 11);

		assertThat(asked(proposal), is(true));
		assertThat(asked(prevote), is(true));

		engine.onMessage(decision, 12);
		app.commit(block);

		assertThat(asked(decision), is(true));

		engine.start(2, validators, 20);

		assertThat(asked(proposal), is(false));
		assertThat(asked(prevote), is(false));
		assertThat(asked(decision), is(false));
	}
}
