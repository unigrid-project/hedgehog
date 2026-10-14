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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

final class ConsensusFixtures {
	static final byte[] CHAIN = Digests.sha512("consensus test chain".getBytes(StandardCharsets.US_ASCII));

	private ConsensusFixtures() {
		/* Static helpers only */
	}

	static ValidatorSet validators(int count) {
		return new ValidatorSet(LedgerFixtures.seeds(1, count).stream().map(Ed25519::publicKey).toList());
	}

	/* Signed by the proposer of the view in a set of four validators, at the first height */
	static Proposal proposal(int view, int validRound, Block block) {
		return proposal(view, validRound, block, List.of());
	}

	static Proposal proposal(int view, int validRound, Block block, List<Ballot> polka) {
		return Proposal.signed(LedgerFixtures.seed(1 + (view % 4)), CHAIN, 1, view, validRound, block, polka);
	}

	static Ballot ballot(Ballot.Type type, int validator, int view, Block block) {
		return Ballot.signed(type, LedgerFixtures.seed(validator), CHAIN, 1, view,
			block == null ? null : block.hash());
	}

	static List<Ballot> ballots(List<Action> actions) {
		return actions.stream().filter(a -> a instanceof Action.Broadcast b && b.message() instanceof Ballot)
			.map(a -> (Ballot) ((Action.Broadcast) a).message()).toList();
	}

	static List<Proposal> proposalsSent(List<Action> actions) {
		return actions.stream().filter(a -> a instanceof Action.Broadcast b && b.message() instanceof Proposal)
			.map(a -> (Proposal) ((Action.Broadcast) a).message()).toList();
	}

	static Block decidedBlock(List<Action> actions) {
		return actions.stream().filter(a -> a instanceof Action.Decide).map(a -> ((Action.Decide) a).block())
			.findFirst().orElseThrow();
	}

	static boolean decides(List<Action> actions) {
		return actions.stream().anyMatch(a -> a instanceof Action.Decide);
	}

	/* An application with a chain of blocks that carry nothing: it builds them, checks them and knows how many
	   it wants before it has no more work */
	static final class FakeApp implements ConsensusApp {
		final List<Block> chain = new ArrayList<>();
		final AccountKey self;
		ValidatorSet validators;
		int goal;
		boolean refuse;

		FakeApp(ValidatorSet validators, AccountKey self, int goal) {
			this.validators = validators;
			this.self = self;
			this.goal = goal;
		}

		byte[] tipHash() {
			return chain.isEmpty() ? new byte[Digests.HASH_SIZE] : chain.get(chain.size() - 1).hash();
		}

		long nextHeight() {
			return chain.size() + 1L;
		}

		Block block(long height, int view, AccountKey proposer, long time, String note) {
			return Block.builder().height(height).view(view).previousHash(tipHash()).time(time)
				.stateRoot(Digests.sha512(note.getBytes(StandardCharsets.US_ASCII)))
				.transactionRoot(new byte[Digests.HASH_SIZE]).transactions(List.of()).proposer(proposer).build();
		}

		@Override
		public Optional<Block> propose(long height, int view, long time) {
			return chain.size() < goal ? Optional.of(block(height, view, self, time, "block of " + self))
				: Optional.empty();
		}

		@Override
		public Optional<String> validate(Block block) {
			if (refuse) {
				return Optional.of("Refused by the test");
			}

			if (block.getHeight() != nextHeight() || !Arrays.equals(block.getPreviousHash(), tipHash())) {
				return Optional.of("Not the next block");
			}

			return block.getProposer().equals(validators.proposerAt(block.getHeight(), block.getView()))
				? Optional.empty() : Optional.of("Not from the proposer of its view");
		}

		@Override
		public Optional<String> validateCertified(Block block) {
			final Optional<String> rejection = validate(block);
			final List<Endorsement> endorsements = block.getEndorsements();

			if (rejection.isPresent()) {
				return rejection;
			}

			if (block.getCertificate().view() < block.getView()) {
				return Optional.of("Certificate from an earlier view");
			}

			final boolean right = endorsements.size() >= validators.quorum()
				&& new HashSet<>(endorsements.stream().map(Endorsement::signer).toList()).size() == endorsements.size()
				&& endorsements.stream().allMatch(e -> validators.contains(e.signer()) && block.isSigned(e, CHAIN));

			return right ? Optional.empty() : Optional.of("Certificate is not right");
		}

		@Override
		public boolean hasWork(long time) {
			return chain.size() < goal;
		}

		void commit(Block block) {
			chain.add(block);
		}
	}
}
