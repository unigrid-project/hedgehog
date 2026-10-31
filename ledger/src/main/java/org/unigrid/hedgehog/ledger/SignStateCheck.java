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

import java.util.Arrays;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;

/* Whether a sign state that a node takes up again in Consensus.restore() is one this node could have written: what it
   holds as signed is its own, its next view is above every view signed in, and every precommit for a block is
   covered by the lock. A node with no key has no self. */
@RequiredArgsConstructor
final class SignStateCheck {
	private final byte[] chainId;
	private final AccountKey self;

	void requireRestorable(SignState state, ValidatorSet newValidators) {
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
		return self != null && set.contains(self);
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
			state.ballots().stream().mapToInt(Ballot::view)).max()
			.orElse(Proposal.NO_ROUND);
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
		final boolean kept = state.ballots().stream().filter(SignStateCheck::isPrecommitForBlock)
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
}
