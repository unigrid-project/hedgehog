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
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import static org.unigrid.hedgehog.ledger.Action.*;

/* One validator's side of the agreement on a block at one height, written after Algorithm 1 of the Tendermint
   paper (arXiv 1807.04938), with its rounds called views. It is a state machine and nothing else: it is told what
   happened and answers with what to do, so it has no clock, no network and no thread of its own, and the same
   inputs always give the same answers. After every event the rules are tried until none applies any more. */
public final class Consensus {
	public static final int MAX_VIEW_AHEAD = 32;

	public enum Step {
		PROPOSE, PREVOTE, PRECOMMIT
	}

	private final ConsensusApp app;
	private final byte[] chainId;
	private final byte[] seed;
	private final AccountKey self;
	private final Timeouts timeouts;
	private final List<BooleanSupplier> rules = List.of(this::decide, this::voteOnProposal, this::lockOnPolka,
		this::precommitNil);

	private long height;
	private ValidatorSet validators;
	private BallotBox box;
	private boolean running;
	private boolean decided;
	private Decision decision;
	private int view;
	private Step step = Step.PROPOSE;
	private Block lockedBlock;
	private int lockedView = -1;
	private Block validBlock;
	private int validView = -1;
	/* Ordered by view, so that when two views have a quorum the same one is found first on every run */
	private final Map<Integer, Proposal> proposals = new TreeMap<>();
	private final Map<ByteBuffer, Boolean> validity = new HashMap<>();
	private final Set<Integer> polkas = new HashSet<>();
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

	/* Begins a height. The first view starts at once when there is work; otherwise nothing runs until a message
	   for this height turns up. */
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
		step = Step.PROPOSE;
		lockedBlock = null;
		lockedView = -1;
		validBlock = null;
		validView = -1;
		proposals.clear();
		validity.clear();
		polkas.clear();
	}

	private void begin() {
		running = true;
		startRound(0);
	}

	private void startRound(int newView) {
		view = newView;
		step = Step.PROPOSE;

		if (isProposer()) {
			tryPropose();
		}
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
	   on it and would refuse any other */
	private Optional<Block> blockToPropose() {
		return validBlock != null ? Optional.of(validBlock) : app.propose(height, view, now);
	}

	private void sendProposal(Block block) {
		final Proposal proposal = Proposal.signed(seed, chainId, height, view, validView, block);

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

	private boolean noteFarAhead(Ballot ballot) {
		if (!validators.contains(ballot.validator()) || !ballot.hasValidSignature(chainId)) {
			return false;
		}

		box.seen(ballot.validator(), ballot.view());
		return true;
	}

	/* Only the first proposal of a view counts, whatever else its proposer signs for that view */
	private boolean accept(Proposal proposal) {
		if (!isFirstFromProposer(proposal) || !proposal.hasValidSignature(chainId)) {
			return false;
		}

		box.seen(proposal.sender(), proposal.view());

		if (!tooFarAhead(proposal.view())) {
			proposals.put(proposal.view(), proposal);
		}

		return true;
	}

	private boolean isFirstFromProposer(Proposal proposal) {
		return proposal.height() == height && !proposals.containsKey(proposal.view())
			&& proposal.sender().equals(validators.proposerAt(height, proposal.view()));
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
		return validity.computeIfAbsent(ByteBuffer.wrap(block.hash()), key -> app.validate(block).isEmpty());
	}

	private void vote(Ballot.Type type, Block block) {
		if (!isValidator()) {
			return;
		}

		final Ballot ballot = Ballot.signed(type, seed, chainId, height, view, block == null ? null : block.hash());

		box.add(ballot);
		out.add(new Broadcast(ballot));
	}

	/* Rule: more than 2/3 of the precommits for the block of a proposal, in the view of that proposal, decide */
	private boolean decide() {
		final Optional<Proposal> committed = proposals.values().stream().filter(this::committedByQuorum).findFirst();

		committed.ifPresent(this::decideOn);
		return committed.isPresent();
	}

	private boolean committedByQuorum(Proposal proposal) {
		return box.quorumFor(Ballot.Type.PRECOMMIT, proposal.view(), proposal.block().hash())
			&& isValid(proposal.block());
	}

	private void decideOn(Proposal proposal) {
		final Block block = proposal.block();
		final Certificate certificate = new Certificate(proposal.view(),
			box.endorsements(proposal.view(), block.hash()));

		finish(new Decision(block.toBuilder().certificate(certificate).build()));
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
			|| box.quorumFor(Ballot.Type.PREVOTE, proposal.validRound(), proposal.block().hash());
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
}
