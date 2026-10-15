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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import static org.unigrid.hedgehog.ledger.Action.*;

/* One validator's side of the agreement on a block at one height, written after Algorithm 1 of the Tendermint
   paper (arXiv 1807.04938), with its rounds called views. It is a state machine and nothing else: it is told what
   happened and answers with what to do, so it has no clock, no network and no thread of its own, and the same
   inputs always give the same answers. After every event the rules are tried until none applies any more.

   What the driver has to do for it, as the engine does none of it itself: it never sends anything twice, and a node
   that has precommitted nil waits for more precommits with no timer running, so the driver calls resume() or
   outbox() now and then, which does no harm when repeated, and serves the decided blocks to a node that is behind.
   It is not thread-safe, so events come one at a time. evidence() is lost at the next start(), so it is read before
   that. The ballot box is wiped by start() as well, so a node that starts a height afresh has signed nothing in it.

   What a node signed must outlive it: snapshot() hands out that and where the node stands, and restore() takes it
   back. The caller keeps the snapshot of an event on stable storage before it lets any action of that event out, so
   that a node which comes back from it, whatever it lost of its last event, never signs anything different for a view
   it has signed in. The rules back this up: they never sign a second ballot of one type, or a second proposal, for
   a view, but say again what they hold. restore() is an event like any other: its actions can hold new signatures,
   such as the prevote for a proposal of its own that it took back or the proposal that begin() makes, so the driver
   snapshots and keeps the state after restore() too, before it performs the actions that came back. The driver also
   compares the height of the state with the tip of its ledger: a snapshot of an engine that has decided restores as
   running and not decided, and its peers tell it the decision again.

   A chain with nothing to do stays quiet. When the precommit timeout of a view runs out and the node has no work,
   no lock, no valid block, no decision it refused, holds no proposal of the view or of the next one and no ballot
   of the next view, it starts no further view: it rests, with no timer, until work or a message of the height
   wakes it in the first view it has signed nothing in. A peer that is still running may have lost a message of the
   node that rests, so while it rests resume() says again what the node signed for a window of three precommit
   timeouts of its view, but at least 5 and at most 60 seconds, and after that it is silent. The driver therefore
   ticks about once a second, which puts at least a few ticks in every window; that is the contract of the
   simulator's tick of one second. What the node signed stays in outbox(). */
public final class Consensus {
	public static final int MAX_VIEW_AHEAD = 32;
	private static final int RESEND_PRECOMMIT_TIMEOUTS = 3;

	/* A tick of the driver is about a second, so the shortest window holds a few of them however short the timeouts
	   are, and the longest keeps a rest in a view with a very long timeout from sending again for minutes */
	private static final long MIN_RESEND_MILLIS = 5_000;
	private static final long MAX_RESEND_MILLIS = 60_000;

	public enum Step {
		PROPOSE, PREVOTE, PRECOMMIT
	}

	private record Refusal(int view, ByteBuffer hash) {
	}

	private final ConsensusApp app;
	private final byte[] chainId;
	private final byte[] seed;
	private final AccountKey self;
	private final Timeouts timeouts;
	private final List<BooleanSupplier> rules = List.of(this::decide, this::skip, this::voteOnProposal,
		this::lockOnPolka, this::precommitNil, this::armPrevoteTimer, this::armPrecommitTimer);

	private long height;
	private ValidatorSet validators;
	private BallotBox box;
	private boolean running;
	private boolean decided;
	private Decision decision;
	private int view;
	/* The first view the node has signed nothing in, which is where a node that rested begins again. It moves past a
	   view as that view starts, before anything is signed in it, so it is above every view signed in; only the last
	   view has none after it, and a node never rests there. */
	private int nextView;
	/* Until when a node that rests says again what it signed */
	private long resendUntil = Long.MIN_VALUE;
	private Step step = Step.PROPOSE;
	private Block lockedBlock;
	private int lockedView = -1;
	private Block validBlock;
	private int validView = -1;
	/* Ordered by view, so that when two views have a quorum the same one is found first on every run */
	private final TreeMap<Integer, Proposal> proposals = new TreeMap<>();
	private final Set<ByteBuffer> validBlocks = new HashSet<>();
	/* A refusal can be a matter of this node's clock, so it is kept for the view it was made in and no longer */
	private final Set<ByteBuffer> refusedBlocks = new HashSet<>();
	private final Set<Refusal> refusedDecisions = new HashSet<>();
	private final Map<Integer, Boolean> proofs = new HashMap<>();
	private final Set<Integer> polkas = new HashSet<>();
	private final Set<Integer> prevoteTimers = new HashSet<>();
	private final Set<Integer> precommitTimers = new HashSet<>();
	private List<Action> out = new ArrayList<>();
	private long now;

	/* A null seed is a node that follows and decides but never votes */
	public Consensus(ConsensusApp app, byte[] chainId, byte[] seed, Timeouts timeouts) {
		this.app = app;
		this.chainId = chainId.clone();
		this.seed = seed == null ? null : seed.clone();
		this.self = seed == null ? null : Ed25519.publicKey(seed);
		this.timeouts = timeouts;
	}

	/* Begins a height. The first view starts at once when there is work; otherwise nothing runs until work turns up
	   or a message for this height does. */
	public List<Action> start(long newHeight, ValidatorSet newValidators, long at) {
		return run(at, () -> {
			reset(newHeight, newValidators);

			if (app.hasWork(at)) {
				begin();
			}
		});
	}

	public List<Action> onMessage(Message message, long at) {
		return run(at, () -> receive(message));
	}

	/* There is work now: a quiet node starts the first view it has not signed in, and a proposer that had nothing to
	   propose does it */
	public List<Action> onWork(long at) {
		return run(at, () -> {
			if (height != 0 && !decided && app.hasWork(at)) {
				beginOrPropose();
			}
		});
	}

	public List<Action> onTimeout(Timeout timeout, long at) {
		return run(at, () -> {
			if (running && !decided && timeout.height() == height && timeout.view() == view) {
				expire(timeout.kind());
			}
		});
	}

	/* The timers are gone, say after a restart of the driver, but the state of the height is not: the timers that
	   were armed are set again and what this node said is said again, without voting anew. A node that rests says it
	   again for a few precommit timeouts and then no more, so that a peer that lost a message can still be given
	   it while the chain goes silent in the end. */
	public List<Action> resume(long at) {
		return run(at, this::rearm);
	}

	private void rearm() {
		if (running && !decided) {
			out.addAll(armedTimers());
			sayAgain();
		} else if (!decided && now <= resendUntil) {
			sayAgain();
		}
	}

	private void sayAgain() {
		outbox().forEach(message -> out.add(new Broadcast(message)));
	}

	/* What this node has signed at this height and where it stands, as a value that the caller keeps. A message of a
	   view above the node's own is left out: it is not this engine's signature but one that someone relayed, say from a
	   second place where the key is in use. A relayed precommit for a block with this key that no lock of this engine
	   covers makes a state that restore() refuses, which is what stops a validator whose key runs in two places. */
	public SignState snapshot() {
		final List<Proposal> proposed = ownProposals().stream().filter(proposal -> proposal.view() <= view).toList();
		final List<Ballot> voted = ownBallots().stream().filter(ballot -> ballot.view() <= view).toList();

		return new SignState(height, running, view, step, nextView, lockedBlock, lockedView, validBlock, validView,
			proposed, voted);
	}

	/* Takes a height up again from a snapshot, like start() followed by resume(), but with the view, the step, the lock
	   and what the node signed as they were. A node at rest stays at rest and says what it signed again for a fresh
	   window, as its peers may have lost the messages it sent before it went down; if there is work it begins the next
	   view, or, when it runs and is the proposer of its view without a proposal, proposes, as onWork() does. A state
	   that this node could not have written is refused, and then nothing is changed. */
	public List<Action> restore(SignState state, ValidatorSet newValidators, long at) {
		requireRestorable(state, newValidators);

		return run(at, () -> {
			reset(state.height(), newValidators);
			install(state);
			rearm();

			if (app.hasWork(at)) {
				beginOrPropose();
			}
		});
	}

	/* What this node has said at this height, for a resend: the decision once there is one */
	public List<Message> outbox() {
		if (decision != null) {
			return List.of(decision);
		}

		final List<Message> own = new ArrayList<>(ownProposals());

		own.addAll(ownBallots());
		return own;
	}

	private List<Proposal> ownProposals() {
		return proposals.values().stream().filter(proposal -> proposal.sender().equals(self)).toList();
	}

	private List<Ballot> ownBallots() {
		return box == null || self == null ? List.of() : box.ballotsBy(self);
	}

	public long height() {
		return height;
	}

	public int view() {
		return view;
	}

	public Step step() {
		return step;
	}

	int lockedView() {
		return lockedView;
	}

	boolean running() {
		return running;
	}

	int nextView() {
		return nextView;
	}

	int storedBallots() {
		return box == null ? 0 : box.size();
	}

	public List<BallotBox.Equivocation> evidence() {
		return box == null ? List.of() : box.evidence();
	}

	private List<Action> run(long at, Runnable event) {
		now = at;
		out = new ArrayList<>();
		event.run();
		settle();
		return out;
	}

	/* A rule that fires changes the state so that it cannot fire again for the same reason, so this ends */
	private void settle() {
		boolean applied = true;

		while (applied && running && !decided) {
			applied = rules.stream().anyMatch(BooleanSupplier::getAsBoolean);
		}
	}

	private void reset(long newHeight, ValidatorSet newValidators) {
		height = newHeight;
		validators = newValidators;
		box = new BallotBox(newHeight, newValidators, chainId);
		running = false;
		decided = false;
		decision = null;
		view = 0;
		nextView = 0;
		resendUntil = Long.MIN_VALUE;
		step = Step.PROPOSE;
		lockedBlock = null;
		lockedView = -1;
		validBlock = null;
		validView = -1;
		proposals.clear();
		validBlocks.clear();
		refusedBlocks.clear();
		refusedDecisions.clear();
		proofs.clear();
		polkas.clear();
		prevoteTimers.clear();
		precommitTimers.clear();
	}

	private void requireRestorable(SignState state, ValidatorSet newValidators) {
		if (state.height() == 0) {
			throw new IllegalArgumentException("A state of height 0 holds nothing to restore");
		}

		requireOwnMessages(state, newValidators);
		requireViewsKept(state);
		requireLocksKept(state);
	}

	/* What the state holds as signed is taken for this node's word when it is said again, so it has to be its own */
	private void requireOwnMessages(SignState state, ValidatorSet newValidators) {
		final boolean holdsNothing = state.proposals().isEmpty() && state.ballots().isEmpty();

		if (!holdsNothing && !canSignIn(newValidators)) {
			throw new IllegalArgumentException("This node cannot sign at height " + state.height()
				+ ", yet the state holds messages it signed");
		}

		requireOwnProposals(state, newValidators);
		requireOwnBallots(state);
	}

	private boolean canSignIn(ValidatorSet set) {
		return seed != null && set.contains(self);
	}

	private void requireOwnProposals(SignState state, ValidatorSet newValidators) {
		if (!state.proposals().stream().allMatch(proposal -> signedAsProposer(proposal, newValidators))) {
			throw new IllegalArgumentException(
				"A proposal of the state is not signed by this node as the proposer of its view");
		}
	}

	private boolean signedAsProposer(Proposal proposal, ValidatorSet newValidators) {
		return proposal.sender().equals(self) && proposal.hasValidSignature(chainId)
			&& proposal.sender().equals(newValidators.proposerAt(proposal.height(), proposal.view()));
	}

	private void requireOwnBallots(SignState state) {
		if (!state.ballots().stream().allMatch(this::signedAsVoter)) {
			throw new IllegalArgumentException("A ballot of the state is not signed by this node");
		}
	}

	private boolean signedAsVoter(Ballot ballot) {
		return ballot.validator().equals(self) && ballot.hasValidSignature(chainId);
	}

	/* The next view is where a node at rest begins again, so a state in which it is not above every view the node signed
	   in, or is below the view of the state, would take the node back to a view it has been in. Only the last view has
	   none above it. */
	private static void requireViewsKept(SignState state) {
		final int signedIn = IntStream.concat(state.proposals().stream().mapToInt(Proposal::view),
			state.ballots().stream().mapToInt(Ballot::view)).max().orElse(-1);
		final boolean aboveSigned = state.nextView() > signedIn || state.view() == Integer.MAX_VALUE;

		if (!aboveSigned || state.nextView() < state.view()) {
			throw new IllegalArgumentException("The next view " + state.nextView() + " is not above the views "
				+ "signed in (" + signedIn + ") and the view " + state.view());
		}
	}

	/* A precommit for a block binds the node to it from that view on, so the lock is on that block in that view or on
	   some block in a later one; a state without it would let the node vote for another block after this one was
	   decided */
	private static void requireLocksKept(SignState state) {
		final boolean kept = state.ballots().stream().filter(Consensus::isPrecommitForBlock)
			.allMatch(precommit -> locksOn(state, precommit));

		if (!kept) {
			throw new IllegalArgumentException("The state holds a precommit for a block it does not lock on");
		}
	}

	private static boolean isPrecommitForBlock(Ballot ballot) {
		return ballot.type() == Ballot.Type.PRECOMMIT && !ballot.isNil();
	}

	private static boolean locksOn(SignState state, Ballot precommit) {
		final Block locked = state.lockedBlock();

		return locked != null && (state.lockedView() > precommit.view()
			|| state.lockedView() == precommit.view() && Arrays.equals(locked.hash(), precommit.blockHash()));
	}

	private void install(SignState state) {
		running = state.running();
		view = state.view();
		step = state.step();
		nextView = state.nextView();
		lockedBlock = state.lockedBlock();
		lockedView = state.lockedView();
		validBlock = state.validBlock();
		validView = state.validView();
		state.proposals().forEach(this::keepOwn);
		state.ballots().forEach(box::add);

		if (running) {
			armTimerOfStep();
		} else {
			resendUntil = now + resendWindow();
		}
	}

	/* The timers died with the process, and the quorum that armed the one of a late step is not in the state, so it is
	   armed again: it only takes the node on to a vote for no block or to the next view. Without it a node that comes
	   back after its peers stopped saying things again would wait for a quorum that never turns up. */
	private void armTimerOfStep() {
		if (step == Step.PREVOTE) {
			prevoteTimers.add(view);
		} else if (step == Step.PRECOMMIT) {
			precommitTimers.add(view);
		}
	}

	private void keepOwn(Proposal proposal) {
		proposals.put(proposal.view(), proposal);
		box.seen(self, proposal.view());
	}

	private void begin() {
		running = true;
		startRound(nextView);
	}

	private void beginOrPropose() {
		if (running) {
			proposeIfDue();
		} else {
			begin();
		}
	}

	/* Every node waits for the proposal of a view, a proposer too, as it may have had nothing to put forward */
	private void startRound(int newView) {
		view = newView;
		nextView = newView == Integer.MAX_VALUE ? newView : newView + 1;
		step = Step.PROPOSE;
		refusedBlocks.clear();
		refusedDecisions.clear();

		if (isProposer()) {
			tryPropose();
		}

		out.add(proposeTimer());
	}

	private void proposeIfDue() {
		if (step == Step.PROPOSE && isProposer()) {
			tryPropose();
		}
	}

	private Schedule proposeTimer() {
		return new Schedule(new Timeout(Timeout.Kind.PROPOSE, height, view), timeouts.propose(view));
	}

	private Schedule prevoteTimer() {
		return new Schedule(new Timeout(Timeout.Kind.PREVOTE, height, view), timeouts.prevote(view));
	}

	private Schedule precommitTimer() {
		return new Schedule(new Timeout(Timeout.Kind.PRECOMMIT, height, view), timeouts.precommit(view));
	}

	private List<Action> armedTimers() {
		final List<Action> timers = new ArrayList<>();

		if (step == Step.PROPOSE) {
			timers.add(proposeTimer());
		}

		if (step == Step.PREVOTE && prevoteTimers.contains(view)) {
			timers.add(prevoteTimer());
		}

		if (precommitTimers.contains(view)) {
			timers.add(precommitTimer());
		}

		return timers;
	}

	private void expire(Timeout.Kind kind) {
		switch (kind) {
			case PROPOSE -> moveOn(Step.PROPOSE, Ballot.Type.PREVOTE, Step.PREVOTE);
			case PREVOTE -> moveOn(Step.PREVOTE, Ballot.Type.PRECOMMIT, Step.PRECOMMIT);
			case PRECOMMIT -> startNextView();
		}
	}

	/* A step that has been left in the meantime has nothing to give up on */
	private void moveOn(Step from, Ballot.Type vote, Step to) {
		if (step == from) {
			vote(vote, null);
			step = to;
		}
	}

	/* The last view has no successor: adding one would wrap around to a negative view, and a node that rested in it
	   would begin it again and sign in it a second time */
	private void startNextView() {
		if (view == Integer.MAX_VALUE) {
			return;
		}

		if (goesQuiet()) {
			running = false;
			resendUntil = now + resendWindow();
		} else {
			startRound(view + 1);
		}
	}

	private long resendWindow() {
		final long wanted = RESEND_PRECOMMIT_TIMEOUTS * timeouts.precommit(view);

		return Math.clamp(wanted, MIN_RESEND_MILLIS, MAX_RESEND_MILLIS);
	}

	/* Nothing is left to wait for or to put forward: no work, no block the node is bound to or would offer again, and
	   no decision it refused for now. The refusals are those of this view, as they are forgotten when a view starts and
	   asked for again by the rules that follow. */
	private boolean goesQuiet() {
		return !app.hasWork(now) && lockedBlock == null && validBlock == null && refusedDecisions.isEmpty()
			&& holdsNothingOfThisViewOrAhead();
	}

	/* A proposal of this view may still be wanted decided. A peer that is in the next view, with a proposal of it or
	   a ballot, has got ahead and has work, and a node that rested would never be woken to answer what it holds
	   already. Only the next view counts: anything further on can be signed by any validator, or by the proposer of one
	   view in the window, and must not be able to keep the chain awake for many views, while a peer that really is
	   further on has signed in the next view as well. */
	private boolean holdsNothingOfThisViewOrAhead() {
		return !proposals.containsKey(view) && !proposals.containsKey(view + 1) && !box.holdsAnyIn(view + 1);
	}

	private boolean isValidator() {
		return seed != null && validators.contains(self);
	}

	private boolean isProposer() {
		return isValidator() && validators.proposerAt(height, view).equals(self);
	}

	private void tryPropose() {
		if (!proposals.containsKey(view)) {
			blockToPropose().ifPresent(this::sendProposal);
		}
	}

	/* A block that already got more than 2/3 of the prevotes is put forward again, as validators may be locked
	   on it and would refuse any other. The prevotes that prove it are the ones this node holds, and after a restart
	   that is its own alone, so it offers nothing in this view rather than a proposal it could not vote for itself;
	   the proposer of the next view carries the proof. */
	private Optional<Block> blockToPropose() {
		if (validBlock == null) {
			return app.propose(height, view, now);
		}

		return Optional.of(validBlock).filter(block -> box.quorumFor(Ballot.Type.PREVOTE, validView, block.hash()));
	}

	/* A new block has no valid view, so there are no prevotes to carry along with it */
	private void sendProposal(Block block) {
		final List<Ballot> polka = box.ballotsFor(Ballot.Type.PREVOTE, validView, block.hash());
		final Proposal proposal = Proposal.signed(seed, chainId, height, view, validView, block, polka);

		proposals.put(view, proposal);
		box.seen(self, view);
		out.add(new Broadcast(proposal));
	}

	private void receive(Message message) {
		if (decided || height == 0) {
			return;
		}

		/* A decision ends the height, so it must not start a view */
		if (accept(message) && !running && !decided) {
			begin();
		}
	}

	private boolean accept(Message message) {
		return switch (message) {
			case Proposal proposal -> accept(proposal);
			case Ballot ballot -> accept(ballot);
			case Decision other -> accept(other);
		};
	}

	/* Whether the ballot is new and right. A ballot that is already known is cheap to drop; a ballot beyond the
	   window is not kept, but it is checked first, as it still counts as the validator having reached that view. */
	private boolean accept(Ballot ballot) {
		if (ballot.height() != height || box.has(ballot)) {
			return false;
		}

		return tooFarAhead(ballot.view()) ? noteFarAhead(ballot) : box.add(ballot) == BallotBox.Result.ADDED;
	}

	/* Not kept, but the validator is known to have reached the view; the ballot is news only if that is a view it was
	   not seen in, so that playing the same ballot back does not wake a node again */
	private boolean noteFarAhead(Ballot ballot) {
		if (!validators.contains(ballot.validator()) || !ballot.hasValidSignature(chainId)) {
			return false;
		}

		return box.seen(ballot.validator(), ballot.view());
	}

	/* Only the first proposal of a view counts, whatever else its proposer signs for that view. One beyond the window
	   is not kept, so it is news only if it takes its proposer to a view it was not seen in, or anyone could play the
	   same proposal back to wake a node over and over. */
	private boolean accept(Proposal proposal) {
		if (!isNewFromProposer(proposal) || !headerAgrees(proposal) || !proposal.hasValidSignature(chainId)) {
			return false;
		}

		final boolean raised = box.seen(proposal.sender(), proposal.view());

		if (tooFarAhead(proposal.view())) {
			return raised;
		}

		proposals.put(proposal.view(), proposal);
		return true;
	}

	private boolean isNewFromProposer(Proposal proposal) {
		return proposal.height() == height && !proposals.containsKey(proposal.view())
			&& proposal.sender().equals(validators.proposerAt(height, proposal.view()));
	}

	/* The header of a block is not signed by anyone, so nothing else ties it to the vote. A new block is of the view
	   it is proposed in; one put forward again keeps the view it was first proposed in, which is no later than the
	   view where it got its prevotes. A block of a later view than its certificate could never be committed. */
	private static boolean headerAgrees(Proposal proposal) {
		final int headerView = proposal.block().getView();

		return proposal.validRound() == -1 ? headerView == proposal.view() : headerView <= proposal.validRound();
	}

	private boolean accept(Decision other) {
		final Block block = other.block();

		if (block.getHeight() != height || app.validateCertified(block).isPresent()) {
			return false;
		}

		finish(other);
		return true;
	}

	private boolean tooFarAhead(int otherView) {
		return (long) otherView > (long) view + MAX_VIEW_AHEAD;
	}

	private void finish(Decision reached) {
		decided = true;
		decision = reached;
		out.add(new Broadcast(reached));
		out.add(new Decide(reached.block()));
	}

	private boolean isValid(Block block) {
		final ByteBuffer key = ByteBuffer.wrap(block.hash());

		return validBlocks.contains(key) || (!refusedBlocks.contains(key) && askIfValid(block, key));
	}

	private boolean askIfValid(Block block, ByteBuffer key) {
		final boolean valid = app.validate(block).isEmpty();

		(valid ? validBlocks : refusedBlocks).add(key);
		return valid;
	}

	/* A ballot of this type that the node signed in this view is said again, never replaced: a node that was restored
	   from a state a step behind its ballots would otherwise sign a second one, which is what makes a validator
	   equivocate */
	private void vote(Ballot.Type type, Block block) {
		if (isValidator()) {
			out.add(new Broadcast(heldBallot(type).orElseGet(() -> sign(type, block))));
		}
	}

	private Optional<Ballot> heldBallot(Ballot.Type type) {
		return ownBallots().stream().filter(ballot -> ballot.type() == type && ballot.view() == view).findFirst();
	}

	private Ballot sign(Ballot.Type type, Block block) {
		final Ballot ballot = Ballot.signed(type, seed, chainId, height, view, block == null ? null : block.hash());

		box.add(ballot);
		return ballot;
	}

	/* Rule: more than 2/3 of the precommits for the block of a proposal, in the view of that proposal, decide, as long
	   as the application takes the block with the certificate they make, which is what every node asks of a decision */
	private boolean decide() {
		return proposals.values().stream().filter(this::committedByQuorum).anyMatch(this::decideOn);
	}

	private boolean committedByQuorum(Proposal proposal) {
		return box.quorumFor(Ballot.Type.PRECOMMIT, proposal.view(), proposal.block().hash())
			&& !refusedDecisions.contains(refusalOf(proposal));
	}

	private boolean decideOn(Proposal proposal) {
		final Block certified = certify(proposal);

		if (app.validateCertified(certified).isPresent()) {
			refusedDecisions.add(refusalOf(proposal));
			return false;
		}

		finish(new Decision(certified));
		return true;
	}

	private static Refusal refusalOf(Proposal proposal) {
		return new Refusal(proposal.view(), ByteBuffer.wrap(proposal.block().hash()));
	}

	private Block certify(Proposal proposal) {
		final Block block = proposal.block();
		final Certificate certificate = new Certificate(proposal.view(),
			box.endorsements(proposal.view(), block.hash()));

		return block.toBuilder().certificate(certificate).build();
	}

	/* Rules for the proposal of the current view while this node still waits for it: a new block is voted for when
	   the node is not locked on another; a block that is put forward again needs the prevote quorum it names */
	private boolean voteOnProposal() {
		final Proposal proposal = proposals.get(view);

		if (step != Step.PROPOSE || proposal == null || !readyToVote(proposal)) {
			return false;
		}

		vote(Ballot.Type.PREVOTE, acceptable(proposal) ? proposal.block() : null);
		step = Step.PREVOTE;
		return true;
	}

	private boolean readyToVote(Proposal proposal) {
		return proposal.validRound() == -1
			|| box.quorumFor(Ballot.Type.PREVOTE, proposal.validRound(), proposal.block().hash())
			|| proofHolds(proposal);
	}

	/* A re-proposal may carry the prevotes that make up its quorum, for a node that never saw them: the quorum
	   counts when more than 2/3 of the validators, each once, signed for this block in that view on this chain.
	   It is worked out once per view and not kept in the ballot box. */
	private boolean proofHolds(Proposal proposal) {
		return proofs.computeIfAbsent(proposal.view(), key -> checkProof(proposal));
	}

	/* Every validator prevotes once in a view, so an honest proof never holds more ballots than there are validators;
	   a longer one is refused before a signature is looked at, as it can only be there to make the node work */
	private boolean checkProof(Proposal proposal) {
		return proposal.polka().size() <= validators.size() && reachesQuorum(proposal.polka());
	}

	private boolean reachesQuorum(List<Ballot> polka) {
		final Set<AccountKey> signers = new HashSet<>();

		for (final Ballot vote : polka) {
			if (isNewSigner(vote, signers) && vote.hasValidSignature(chainId)) {
				signers.add(vote.validator());
			}

			if (signers.size() >= validators.quorum()) {
				return true;
			}
		}

		return false;
	}

	private boolean isNewSigner(Ballot vote, Set<AccountKey> signers) {
		return validators.contains(vote.validator()) && !signers.contains(vote.validator());
	}

	/* A node locked on a block votes only for it, unless the proposal proves that more than 2/3 prevoted for the
	   other block in a view at or after the lock. For a new block (valid round -1) that means: not locked at all. */
	private boolean acceptable(Proposal proposal) {
		final Block block = proposal.block();
		final boolean sameAsLocked = lockedBlock != null && Arrays.equals(lockedBlock.hash(), block.hash());

		return isValid(block) && (lockedView <= proposal.validRound() || sameAsLocked);
	}

	/* Rule: more than 2/3 of the prevotes for the proposal of the current view lock the node on that block and
	   make it precommit it, and the block becomes the valid one to put forward again */
	private boolean lockOnPolka() {
		final Proposal proposal = proposals.get(view);

		if (proposal == null || !hasNewPolka(proposal.block())) {
			return false;
		}

		polkas.add(view);

		if (step == Step.PREVOTE) {
			lockAndPrecommit(proposal.block());
		}

		validBlock = proposal.block();
		validView = view;
		return true;
	}

	private boolean hasNewPolka(Block block) {
		return step != Step.PROPOSE && !polkas.contains(view)
			&& box.quorumFor(Ballot.Type.PREVOTE, view, block.hash()) && isValid(block);
	}

	private void lockAndPrecommit(Block block) {
		lockedBlock = block;
		lockedView = view;
		vote(Ballot.Type.PRECOMMIT, block);
		step = Step.PRECOMMIT;
	}

	/* Rule: more than 2/3 of the prevotes for no block make the node precommit no block */
	private boolean precommitNil() {
		if (step != Step.PREVOTE || !box.quorumFor(Ballot.Type.PREVOTE, view, Ballot.NIL)) {
			return false;
		}

		vote(Ballot.Type.PRECOMMIT, null);
		step = Step.PRECOMMIT;
		return true;
	}

	/* Rule: when more than 1/3 of the validators are already in a later view, go there; at least one of them is
	   honest. The box names a view only when it is later than this one, so the rule cannot fire twice in a row. */
	private boolean skip() {
		final OptionalInt target = box.skipTo(view);

		target.ifPresent(this::startRound);
		return target.isPresent();
	}

	/* Rule: the first time more than 2/3 of the prevotes of the view are in, whatever they are for, the node waits
	   a while longer for the rest before it gives up on a block */
	private boolean armPrevoteTimer() {
		if (step != Step.PREVOTE || !box.quorumAny(Ballot.Type.PREVOTE, view) || !prevoteTimers.add(view)) {
			return false;
		}

		out.add(prevoteTimer());
		return true;
	}

	/* Rule: likewise for the precommits, in whatever step the node is */
	private boolean armPrecommitTimer() {
		if (!box.quorumAny(Ballot.Type.PRECOMMIT, view) || !precommitTimers.add(view)) {
			return false;
		}

		out.add(precommitTimer());
		return true;
	}
}
