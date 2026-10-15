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
import java.util.Collection;
import java.util.List;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Action.*;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PRECOMMIT;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PREVOTE;
import static org.unigrid.hedgehog.ledger.ConsensusFixtures.*;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class ConsensusRestoreTest {
	/* The engine is validator 4, who proposes view 3 and follows in views 0, 1 and 2, with the validators 1, 2 and 3
	   as the others. Validator 1 proposes view 0. */
	private static final Timeouts TIMEOUTS = new Timeouts(3000, 500, 1000, 300, 1500, 700);
	private static final long MUCH_LATER = 40_000;
	private static final long SHORTEST_RESEND_WINDOW = 5000;

	private final ValidatorSet validators = validators(4);
	private final FakeApp app = new FakeApp(validators, key(4), 3);
	private final Consensus engine = new Consensus(app, CHAIN, seed(4), TIMEOUTS);

	private record Restart(Consensus engine, List<Action> actions) {
	}

	private Block proposed(int view, String note) {
		return app.block(1, view, validators.proposerAt(1, view), 100, note);
	}

	private static Timeout timeout(Timeout.Kind kind, int view) {
		return new Timeout(kind, 1, view);
	}

	private static Schedule schedule(Timeout.Kind kind, int view, long delay) {
		return new Schedule(timeout(kind, view), delay);
	}

	private static Ballot prevoteBy(int validator, int view, Block block) {
		return ballot(PREVOTE, validator, view, block);
	}

	private static List<Ballot> ownBallots(Consensus node) {
		return node.outbox().stream().filter(message -> message instanceof Ballot).map(Ballot.class::cast).toList();
	}

	private static List<Action> broadcasts(List<Message> messages) {
		return messages.stream().<Action>map(Broadcast::new).toList();
	}

	private static List<Action> timersThenMessages(List<Action> timers, List<Message> messages) {
		return timersThenMessages(timers, messages, List.of());
	}

	private static List<Action> timersThenMessages(List<Action> before, List<Message> messages, List<Action> after) {
		final List<Action> actions = new ArrayList<>(before);

		actions.addAll(broadcasts(messages));
		actions.addAll(after);
		return actions;
	}

	private Consensus fresh() {
		return new Consensus(app, CHAIN, seed(4), TIMEOUTS);
	}

	private Restart restart(Consensus original, long at) {
		final Consensus again = fresh();

		return new Restart(again, again.restore(original.snapshot(), validators, at));
	}

	private static void assertRestoredAs(Consensus original, Restart restart) {
		final Consensus again = restart.engine();

		assertThat(again.height(), equalTo(original.height()));
		assertThat(again.view(), equalTo(original.view()));
		assertThat(again.step(), equalTo(original.step()));
		assertThat(again.lockedView(), equalTo(original.lockedView()));
		assertThat(again.running(), equalTo(original.running()));
		assertThat(again.nextView(), equalTo(original.nextView()));
		assertThat(again.outbox(), equalTo(original.outbox()));
		assertThat(again.snapshot(), equalTo(original.snapshot()));
	}

	private static SignState stateOf(int view, Consensus.Step step, List<Proposal> proposals, List<Ballot> ballots) {
		return new SignState(1, true, view, step, view + 1, null, -1, null, -1, proposals, ballots);
	}

	private static String refusalOf(Consensus node, SignState state, ValidatorSet validators) {
		return Expect.assertThrows(IllegalArgumentException.class, () -> node.restore(state, validators, 0))
			.getMessage();
	}

	private String refusalOf(SignState state) {
		return refusalOf(fresh(), state, validators);
	}

	/* More than 2/3 of the precommits are in, none for a block, after the node gave up on the proposal of the view */
	private static void noBlockQuorum(Consensus node, int view, long at) {
		node.onTimeout(timeout(Timeout.Kind.PROPOSE, view), at);

		for (int validator = 1; validator <= 3; validator++) {
			node.onMessage(ballot(PRECOMMIT, validator, view, null), at + 1);
		}
	}

	private void lockOnBlockOfViewZero(Block block) {
		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(prevoteBy(1, 0, block), 11);
		engine.onMessage(prevoteBy(2, 0, block), 12);
	}

	/* No work, one message of the height that starts view 0, and a view that ends with nothing in it */
	private void goIdle() {
		app.goal = 0;
		engine.start(1, validators, 0);
		engine.onMessage(prevoteBy(1, 0, null), 5);
		noBlockQuorum(engine, 0, 3005);
		engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 3007);
	}

	private static void assertNoContradiction(List<Ballot> signed, List<Ballot> later) {
		for (final Ballot ballot : later) {
			signed.stream().filter(was -> was.type() == ballot.type() && was.view() == ballot.view())
				.forEach(was -> assertThat(ballot.toString(), ballot.blockHash(), equalTo(was.blockHash())));
		}
	}

	private static List<Ballot> ballotsOf(Collection<List<Action>> events) {
		return events.stream().flatMap(actions -> ballots(actions).stream()).toList();
	}

	@Example
	public void shouldRestoreWithTheProposeTimerArmedAfterStart() {
		engine.start(1, validators, 0);

		final Restart restart = restart(engine, 50);

		assertRestoredAs(engine, restart);
		assertThat(restart.actions(), equalTo(List.of(schedule(Timeout.Kind.PROPOSE, 0, 3000))));
		assertThat(ballots(restart.engine().onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3050)).size(), equalTo(1));
		assertThat(restart.engine().step(), equalTo(Consensus.Step.PREVOTE));
	}

	@Example
	public void shouldRestoreAfterPrevotingAndSayWhatItSignedAgain() {
		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, proposed(0, "a")), 10);

		final Restart restart = restart(engine, 50);

		assertRestoredAs(engine, restart);
		assertThat(restart.engine().step(), equalTo(Consensus.Step.PREVOTE));
		assertThat(restart.actions(), equalTo(broadcasts(engine.outbox())));
		assertThat(restart.actions().size(), equalTo(1));
	}

	@Example
	public void shouldRestoreTheLockAndTheValidBlockAfterAPolkaAndPrecommit() {
		final Block block = proposed(0, "a");

		lockOnBlockOfViewZero(block);

		final Restart restart = restart(engine, 50);
		final SignState state = restart.engine().snapshot();

		assertRestoredAs(engine, restart);
		assertThat(restart.engine().step(), equalTo(Consensus.Step.PRECOMMIT));
		assertThat(restart.engine().lockedView(), equalTo(0));
		assertThat(state.lockedBlock(), equalTo(block));
		assertThat(state.validBlock(), equalTo(block));
		assertThat(state.validView(), equalTo(0));
		assertThat(restart.actions(), equalTo(broadcasts(engine.outbox())));
		assertThat(restart.actions().size(), equalTo(2));
	}

	@Example
	public void shouldRestoreAfterPrecommittingNoBlock() {
		engine.start(1, validators, 0);
		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);
		engine.onMessage(prevoteBy(1, 0, null), 3001);
		engine.onMessage(prevoteBy(2, 0, null), 3002);

		final Restart restart = restart(engine, 50);

		assertRestoredAs(engine, restart);
		assertThat(restart.engine().step(), equalTo(Consensus.Step.PRECOMMIT));
		assertThat(restart.actions(), equalTo(broadcasts(engine.outbox())));
		assertThat(restart.actions().size(), equalTo(2));
	}

	@Example
	public void shouldRestoreInTheNextViewWithItsProposeTimerAfterLeavingAView() {
		engine.start(1, validators, 0);
		noBlockQuorum(engine, 0, 3000);
		engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 4600);

		final Restart restart = restart(engine, 5000);

		assertRestoredAs(engine, restart);
		assertThat(restart.engine().view(), equalTo(1));
		assertThat(restart.engine().nextView(), equalTo(2));
		assertThat(restart.actions(), equalTo(timersThenMessages(List.of(schedule(Timeout.Kind.PROPOSE, 1, 3500)),
			engine.outbox())));
	}

	@Example
	public void shouldRestoreIdleAndOpenAFreshWindowOfSayingAgainWhatItSigned() {
		goIdle();

		final Restart restart = restart(engine, MUCH_LATER);

		assertRestoredAs(engine, restart);
		assertThat(restart.engine().running(), is(false));
		assertThat(engine.resume(MUCH_LATER).isEmpty(), is(true));
		assertThat(restart.actions(), equalTo(broadcasts(engine.outbox())));
		assertThat(restart.actions().size(), equalTo(1));
		assertThat(restart.engine().resume(MUCH_LATER + SHORTEST_RESEND_WINDOW), equalTo(restart.actions()));
		assertThat(restart.engine().resume(MUCH_LATER + SHORTEST_RESEND_WINDOW + 1).isEmpty(), is(true));
		assertThat(restart.engine().running(), is(false));
	}

	@Example
	public void shouldStartTheViewAfterTheOnesItSignedInWhenWorkTurnsUpForAnIdleRestoredNode() {
		goIdle();

		final Restart restart = restart(engine, MUCH_LATER);

		assertThat(restart.engine().nextView(), equalTo(1));

		app.goal = 3;

		assertThat(restart.engine().onWork(MUCH_LATER + 1),
			equalTo(List.of(schedule(Timeout.Kind.PROPOSE, 1, 3500))));
		assertThat(restart.engine().running(), is(true));
		assertThat(restart.engine().view(), equalTo(1));
	}

	@Example
	public void shouldStartTheNextViewAtOnceWhenAnIdleNodeIsRestoredWithWorkWaiting() {
		goIdle();
		app.goal = 3;

		final Restart restart = restart(engine, MUCH_LATER);

		assertThat(restart.engine().running(), is(true));
		assertThat(restart.engine().view(), equalTo(1));
		assertThat(restart.actions(), equalTo(timersThenMessages(List.of(), engine.outbox(),
			List.of(schedule(Timeout.Kind.PROPOSE, 1, 3500)))));
	}

	/* What the rest of the network can tell a node that prevoted one block in view 0: a polka for another block before
	   and after its proposal, one for the block it prevoted, for no block, a mix, and precommits and a later view. Each
	   is the whole story at the node, since a validator's second word in a view is not heard. */
	private List<List<Message>> arrivalsAfterPrevoting(Block one, Block other) {
		return List.of(
			List.of(proposal(0, -1, other), prevoteBy(1, 0, other), prevoteBy(2, 0, other),
				prevoteBy(3, 0, other)),
			List.of(prevoteBy(1, 0, other), prevoteBy(2, 0, other), prevoteBy(3, 0, other),
				proposal(0, -1, other)),
			List.of(proposal(0, -1, one), prevoteBy(1, 0, one), prevoteBy(2, 0, one), prevoteBy(3, 0, one)),
			List.of(prevoteBy(1, 0, null), prevoteBy(2, 0, null), prevoteBy(3, 0, null)),
			List.of(proposal(0, -1, other), prevoteBy(1, 0, other), prevoteBy(2, 0, null), prevoteBy(3, 0, one)),
			List.of(ballot(PRECOMMIT, 1, 0, other), ballot(PRECOMMIT, 2, 0, null), ballot(PRECOMMIT, 3, 0, one)),
			List.of(prevoteBy(1, 1, null), prevoteBy(2, 1, other), proposal(1, -1, proposed(1, "c"))));
	}

	@Example
	public void shouldSignNothingDifferentFromWhatItSignedWhateverArrivesAfterARestart() {
		final Block one = proposed(0, "a");
		final Block other = proposed(0, "b");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, one), 10);

		final List<Ballot> signed = ownBallots(engine);

		for (final List<Message> arrivals : arrivalsAfterPrevoting(one, other)) {
			final Restart restart = restart(engine, 50);
			final List<List<Action>> events = new ArrayList<>(List.of(restart.actions()));

			arrivals.forEach(message -> events.add(restart.engine().onMessage(message, 100)));

			for (final Timeout.Kind kind : Timeout.Kind.values()) {
				events.add(restart.engine().onTimeout(timeout(kind, 0), 9000));
			}

			final List<Ballot> later = ballotsOf(events);

			assertThat(arrivals.toString(), later.size(), greaterThan(1));
			assertNoContradiction(signed, later);
		}
	}

	@Example
	public void shouldNotProposeASecondBlockInTheViewItProposedInWhenRestored() {
		final FakeApp proposerApp = new FakeApp(validators, key(1), 3);
		final Consensus proposer = new Consensus(proposerApp, CHAIN, seed(1), TIMEOUTS);

		proposer.start(1, validators, 0);

		final Consensus again = new Consensus(proposerApp, CHAIN, seed(1), TIMEOUTS);
		final List<Action> restored = again.restore(proposer.snapshot(), validators, 5);

		assertThat(proposalsSent(restored), equalTo(proposalsSent(broadcasts(proposer.outbox()))));
		assertThat(proposalsSent(restored).size(), equalTo(1));
		assertThat(again.outbox(), equalTo(proposer.outbox()));
		assertThat(proposalsSent(again.onWork(6)).isEmpty(), is(true));
		assertThat(proposalsSent(again.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3005)).isEmpty(), is(true));
		assertThat(again.outbox(), equalTo(proposer.outbox()));
	}

	/* A state a step behind its own proposal is not one that the engine writes, but when it is offered another
	   block it must still not sign a second proposal for the view */
	@Example
	public void shouldNotProposeASecondBlockWhenTheStateIsBehindItsOwnProposal() {
		final FakeApp proposerApp = new FakeApp(validators, key(1), 3);
		final Consensus proposer = new Consensus(proposerApp, CHAIN, seed(1), TIMEOUTS);
		final Proposal held = proposal(0, -1, proposerApp.block(1, 0, key(1), 0, "held"));
		final List<Action> restored = proposer.restore(stateOf(0, Consensus.Step.PROPOSE, List.of(held), List.of()),
			validators, 5);

		assertThat(proposalsSent(restored), equalTo(List.of(held)));
		assertThat(proposalsSent(proposer.onWork(6)).isEmpty(), is(true));
		assertThat(proposer.outbox().stream().filter(message -> message instanceof Proposal).toList(),
			equalTo(List.of(held)));
	}

	@Example
	public void shouldPrevoteNilForAnotherBlockInTheNextViewWhenRestoredWithALock() {
		final Block locked = proposed(0, "a");
		final Block other = proposed(1, "b");

		lockOnBlockOfViewZero(locked);

		final Consensus again = restart(engine, 50).engine();
		final List<Ballot> never = nextViewWithProposal(engine, other);
		final List<Ballot> restored = nextViewWithProposal(again, other);

		assertThat(again.lockedView(), equalTo(0));
		assertThat(restored, equalTo(never));
		assertThat(restored.size(), equalTo(1));
		assertThat(restored.get(0).view(), equalTo(1));
		assertThat(restored.get(0).isNil(), is(true));
	}

	private static List<Ballot> nextViewWithProposal(Consensus node, Block block) {
		for (int validator = 1; validator <= 3; validator++) {
			node.onMessage(ballot(PRECOMMIT, validator, 0, null), 200);
		}

		node.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 1700);

		return ballots(node.onMessage(proposal(1, -1, block), 1800));
	}

	@Example
	public void shouldRebroadcastTheHeldPrevoteAndSignNothingNewWhenTheStateIsBehindItsBallots() {
		final Block held = proposed(0, "held");
		final Block offered = proposed(0, "offered");
		final Ballot prevote = prevoteBy(4, 0, held);
		final Consensus node = fresh();

		node.restore(stateOf(0, Consensus.Step.PROPOSE, List.of(), List.of(prevote)), validators, 5);

		final List<Action> actions = node.onMessage(proposal(0, -1, offered), 10);

		assertThat(ballots(actions), equalTo(List.of(prevote)));
		assertThat(node.step(), equalTo(Consensus.Step.PREVOTE));
		assertThat(node.outbox(), equalTo(List.of(prevote)));
	}

	@Example
	public void shouldRebroadcastTheHeldPrecommitAndSignNothingNewWhenTheStateIsBehindItsBallots() {
		final Block offered = proposed(0, "offered");
		final Ballot prevote = prevoteBy(4, 0, null);
		final Ballot precommit = ballot(PRECOMMIT, 4, 0, null);
		final Consensus node = fresh();

		node.restore(stateOf(0, Consensus.Step.PREVOTE, List.of(), List.of(prevote, precommit)), validators, 5);
		node.onMessage(proposal(0, -1, offered), 10);
		node.onMessage(prevoteBy(1, 0, offered), 11);
		node.onMessage(prevoteBy(2, 0, offered), 12);

		final List<Ballot> sent = ballots(node.onMessage(prevoteBy(3, 0, offered), 13));

		assertThat(sent, equalTo(List.of(precommit)));
		assertThat(node.outbox(), equalTo(List.of(prevote, precommit)));
	}

	@Example
	public void shouldKeepRunningIntoTheNextViewWhenRestoredWithALockAndNoValidBlock() {
		final Block locked = proposed(0, "a");
		final SignState state = new SignState(1, true, 0, Consensus.Step.PRECOMMIT, 1, locked, 0, null, -1,
			List.of(), List.of(prevoteBy(4, 0, locked), ballot(PRECOMMIT, 4, 0, locked)));

		app.goal = 0;
		engine.restore(state, validators, 5);

		for (int validator = 1; validator <= 3; validator++) {
			engine.onMessage(ballot(PRECOMMIT, validator, 0, null), 6);
		}

		final List<Action> actions = engine.onTimeout(timeout(Timeout.Kind.PRECOMMIT, 0), 2000);

		assertThat(actions, equalTo(List.of(schedule(Timeout.Kind.PROPOSE, 1, 3500))));
		assertThat(engine.running(), is(true));
		assertThat(engine.view(), equalTo(1));
	}

	@Example
	public void shouldSnapshotNothingSignedForAnObserverThatLocks() {
		final Block block = proposed(0, "a");
		final Consensus observer = new Consensus(app, CHAIN, null, TIMEOUTS);

		observer.start(1, validators, 0);
		observer.onMessage(proposal(0, -1, block), 10);

		for (int validator = 1; validator <= 3; validator++) {
			observer.onMessage(prevoteBy(validator, 0, block), 11);
		}

		final SignState state = observer.snapshot();

		assertThat(state.proposals().isEmpty(), is(true));
		assertThat(state.ballots().isEmpty(), is(true));
		assertThat(state.lockedBlock(), equalTo(block));

		final Consensus again = new Consensus(app, CHAIN, null, TIMEOUTS);

		again.restore(state, validators, 50);

		assertThat(again.snapshot(), equalTo(state));
		assertThat(again.lockedView(), equalTo(0));
	}

	@Example
	public void shouldSnapshotNothingBeforeAHeightBegins() {
		final SignState state = engine.snapshot();

		assertThat(state.height(), equalTo(0L));
		assertThat(state.running(), is(false));
		assertThat(state.proposals().isEmpty() && state.ballots().isEmpty(), is(true));
	}

	@Example
	public void shouldNotClaimAMessageOfItsKeyThatIsOfALaterViewThanItsOwn() {
		engine.start(1, validators, 0);
		engine.onMessage(prevoteBy(4, 5, null), 5);
		engine.onMessage(proposal(3, -1, proposed(3, "d")), 6);

		final SignState state = engine.snapshot();

		assertThat(engine.view(), equalTo(0));
		assertThat(engine.outbox().size(), equalTo(2));
		assertThat(state.ballots().isEmpty(), is(true));
		assertThat(state.proposals().isEmpty(), is(true));
	}

	@Example
	public void shouldNotChangeASnapshotThatWasTakenWhenTheEngineMovesOn() {
		engine.start(1, validators, 0);

		final SignState before = engine.snapshot();

		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, 0), 3000);

		assertThat(before.ballots().isEmpty(), is(true));
		assertThat(before.step(), equalTo(Consensus.Step.PROPOSE));
		assertThat(engine.snapshot().ballots().size(), equalTo(1));
	}

	@Example
	public void shouldRestoreInTheLastViewAndStayThere() {
		final int last = Integer.MAX_VALUE;

		engine.start(1, validators, 0);
		engine.onMessage(prevoteBy(1, last, null), 5);
		engine.onMessage(prevoteBy(2, last, null), 6);
		engine.onTimeout(timeout(Timeout.Kind.PROPOSE, last), 9);

		final Restart restart = restart(engine, 50);

		assertRestoredAs(engine, restart);
		assertThat(restart.engine().view(), equalTo(last));
		assertThat(restart.engine().nextView(), equalTo(last));

		for (int validator = 1; validator <= 3; validator++) {
			restart.engine().onMessage(ballot(PRECOMMIT, validator, last, null), 60);
		}

		assertThat(restart.engine().onTimeout(timeout(Timeout.Kind.PRECOMMIT, last), 70).isEmpty(), is(true));
		assertThat(restart.engine().view(), equalTo(last));
		assertThat(restart.engine().running(), is(true));
	}

	@Example
	public void shouldRefuseAProposalSignedByAnotherKey() {
		final Proposal ofAnother = proposal(0, -1, proposed(0, "a"));

		assertThat(refusalOf(stateOf(0, Consensus.Step.PREVOTE, List.of(ofAnother), List.of())),
			containsString("not signed by this node"));
	}

	@Example
	public void shouldRefuseABallotSignedByAnotherKey() {
		assertThat(refusalOf(stateOf(0, Consensus.Step.PREVOTE, List.of(), List.of(prevoteBy(1, 0, null)))),
			containsString("not signed by this node"));
	}

	@Example
	public void shouldRefuseABallotOfItsKeyWithoutASignatureOfIt() {
		final Ballot forged = new Ballot(PREVOTE, 1, 0, Ballot.NIL, key(4), new byte[Ed25519.SIGNATURE_SIZE]);

		assertThat(refusalOf(stateOf(0, Consensus.Step.PREVOTE, List.of(), List.of(forged))),
			containsString("not signed by this node"));
	}

	@Example
	public void shouldRefuseAProposalOfItsKeyWithoutASignatureOfIt() {
		final Proposal forged = new Proposal(1, 3, -1, proposed(3, "d"), List.of(), key(4),
			new byte[Ed25519.SIGNATURE_SIZE]);

		assertThat(refusalOf(stateOf(3, Consensus.Step.PREVOTE, List.of(forged), List.of())),
			containsString("not signed by this node"));
	}

	@Example
	public void shouldRefuseAProposalOfAViewThatIsNotItsToPropose() {
		final Proposal mine = Proposal.signed(seed(4), CHAIN, 1, 0, -1, proposed(0, "a"));

		assertThat(refusalOf(stateOf(0, Consensus.Step.PREVOTE, List.of(mine), List.of())),
			containsString("not signed by this node"));
	}

	@Example
	public void shouldRefuseAStateOfHeightZero() {
		assertThat(refusalOf(SignState.empty(0)), containsString("height"));
	}

	@Example
	public void shouldRefuseSignedMessagesWhenTheNodeHasNoKey() {
		final Consensus observer = new Consensus(app, CHAIN, null, TIMEOUTS);

		assertThat(refusalOf(observer, stateOf(0, Consensus.Step.PREVOTE, List.of(), List.of(prevoteBy(4, 0, null))),
			validators), containsString("cannot sign"));
	}

	@Example
	public void shouldRefuseSignedMessagesWhenTheKeyIsNotOfAValidator() {
		final Consensus outsider = new Consensus(app, CHAIN, seed(5), TIMEOUTS);
		final Ballot ballot = ballot(PREVOTE, 5, 0, null);

		assertThat(refusalOf(outsider, stateOf(0, Consensus.Step.PREVOTE, List.of(), List.of(ballot)), validators),
			containsString("cannot sign"));
	}

	@Example
	public void shouldRefuseANextViewThatIsNotAboveTheViewsItSignedIn() {
		final SignState state = new SignState(1, true, 2, Consensus.Step.PREVOTE, 2, null, -1, null, -1, List.of(),
			List.of(prevoteBy(4, 2, null)));

		assertThat(refusalOf(state), containsString("next view"));
	}

	@Example
	public void shouldRefuseANextViewBelowTheViewOfTheState() {
		final SignState state = new SignState(1, false, 5, Consensus.Step.PROPOSE, 2, null, -1, null, -1, List.of(),
			List.of());

		assertThat(refusalOf(state), containsString("next view"));
	}

	@Example
	public void shouldRefuseAPrecommitForABlockThatTheStateDoesNotLockOn() {
		final Block block = proposed(0, "a");
		final SignState state = stateOf(0, Consensus.Step.PRECOMMIT, List.of(),
			List.of(prevoteBy(4, 0, block), ballot(PRECOMMIT, 4, 0, block)));

		assertThat(refusalOf(state), containsString("lock"));
	}

	@Example
	public void shouldRefuseAPrecommitForAnotherBlockThanTheOneLockedOnInTheSameView() {
		final Block block = proposed(0, "a");
		final SignState state = new SignState(1, true, 0, Consensus.Step.PRECOMMIT, 1, proposed(0, "b"), 0, null, -1,
			List.of(), List.of(prevoteBy(4, 0, block), ballot(PRECOMMIT, 4, 0, block)));

		assertThat(refusalOf(state), containsString("lock"));
	}

	@Example
	public void shouldAcceptAPrecommitForABlockThatALaterLockHasReplaced() {
		final Block old = proposed(0, "a");
		final Block locked = proposed(1, "b");
		final SignState state = new SignState(1, true, 1, Consensus.Step.PRECOMMIT, 2, locked, 1, locked, 1,
			List.of(), List.of(prevoteBy(4, 0, old), ballot(PRECOMMIT, 4, 0, old), prevoteBy(4, 1, locked),
				ballot(PRECOMMIT, 4, 1, locked)));
		final Consensus again = fresh();

		again.restore(state, validators, 5);

		assertThat(again.snapshot(), equalTo(state));
	}

	@Example
	public void shouldLeaveTheEngineAsItWasWhenARestoreIsRefused() {
		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, proposed(0, "a")), 10);

		final SignState before = engine.snapshot();

		refusalOf(engine, SignState.empty(0), validators);
		refusalOf(engine, stateOf(0, Consensus.Step.PREVOTE, List.of(), List.of(prevoteBy(1, 0, null))), validators);

		assertThat(engine.snapshot(), equalTo(before));
	}
}
