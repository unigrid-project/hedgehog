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
import java.util.Collections;
import java.util.List;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.key;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.seed;

public class ProposalTest {
	private static final byte[] CHAIN = new byte[Digests.HASH_SIZE];

	private static Block block(long height, int view) {
		return Block.builder().height(height).view(view).previousHash(new byte[Digests.HASH_SIZE]).time(1)
			.stateRoot(new byte[Digests.HASH_SIZE]).transactionRoot(new byte[Digests.HASH_SIZE])
			.transactions(List.of()).proposer(key(1)).build();
	}

	private static Ballot prevote(int validator, long height, int view, Block block) {
		return Ballot.signed(Ballot.Type.PREVOTE, seed(validator), CHAIN, height, view, block.hash());
	}

	private static List<Ballot> prevotes(long height, int view, Block block) {
		return List.of(prevote(1, height, view, block), prevote(2, height, view, block),
			prevote(3, height, view, block));
	}

	@Example
	public void shouldVerifyOnlyForTheFieldsItWasSignedOver() {
		final Block block = block(4, 0);
		final Proposal proposal = Proposal.signed(seed(1), CHAIN, 4, 2, 0, block);
		final byte[] otherChain = new byte[Digests.HASH_SIZE];

		otherChain[0] = 1;

		assertThat(proposal.hasValidSignature(CHAIN), is(true));
		assertThat(proposal.hasValidSignature(otherChain), is(false));
		assertThat(new Proposal(4, 3, 0, block, List.of(), proposal.sender(), proposal.signature())
			.hasValidSignature(CHAIN), is(false));
		assertThat(new Proposal(4, 2, 1, block, List.of(), proposal.sender(), proposal.signature())
			.hasValidSignature(CHAIN), is(false));
		assertThat(new Proposal(4, 2, 0, block(4, 1), List.of(), proposal.sender(), proposal.signature())
			.hasValidSignature(CHAIN), is(false));
	}

	@Example
	public void shouldBeEqualByContent() {
		final Block block = block(4, 0);
		final Proposal proposal = Proposal.signed(seed(1), CHAIN, 4, 2, 0, block);
		final Proposal same = new Proposal(4, 2, 0, block, List.of(), proposal.sender(), proposal.signature());

		assertThat(proposal, equalTo(same));
		assertThat(proposal.hashCode(), equalTo(same.hashCode()));
		assertThat(Proposal.signed(seed(2), CHAIN, 4, 2, 0, block), not(equalTo(proposal)));
		assertThat(new Proposal(4, 3, 0, block, List.of(), proposal.sender(), proposal.signature()),
			not(equalTo(proposal)));
		assertThat(new Proposal(4, 2, 1, block, List.of(), proposal.sender(), proposal.signature()),
			not(equalTo(proposal)));
		assertThat(new Proposal(4, 2, 0, block(4, 1), List.of(), proposal.sender(), proposal.signature()),
			not(equalTo(proposal)));
	}

	@Example
	public void shouldCarryTheProofOfThePrevotesItNames() {
		final Block block = block(4, 0);
		final List<Ballot> proof = prevotes(4, 1, block);
		final Proposal proposal = Proposal.signed(seed(1), CHAIN, 4, 2, 1, block, proof);

		assertThat(proposal.polka(), equalTo(proof));
		assertThat(Proposal.signed(seed(1), CHAIN, 4, 2, 1, block).polka().isEmpty(), is(true));
	}

	private static Proposal withProof(Proposal proposal, List<Ballot> proof) {
		return new Proposal(proposal.height(), proposal.view(), proposal.validRound(), proposal.block(), proof,
			proposal.sender(), proposal.signature());
	}

	@Example
	public void shouldVerifyWithTheProofItWasSignedWithAndWithoutOne() {
		final Block block = block(4, 0);
		final Proposal bare = Proposal.signed(seed(1), CHAIN, 4, 2, 1, block);
		final Proposal proved = Proposal.signed(seed(1), CHAIN, 4, 2, 1, block, prevotes(4, 1, block));

		assertThat(bare.hasValidSignature(CHAIN), is(true));
		assertThat(proved.hasValidSignature(CHAIN), is(true));
		assertThat(withProof(proved, prevotes(4, 1, block)).hasValidSignature(CHAIN), is(true));
	}

	@Example
	public void shouldNotVerifyOnceTheProofIsStrippedTrimmedSwappedReorderedOrExtended() {
		final Block block = block(4, 0);
		final List<Ballot> proof = prevotes(4, 1, block);
		final Proposal proved = Proposal.signed(seed(1), CHAIN, 4, 2, 1, block, proof);
		final Proposal bare = Proposal.signed(seed(1), CHAIN, 4, 2, 1, block);
		final List<Ballot> reordered = List.of(proof.get(1), proof.get(0), proof.get(2));
		final List<Ballot> extended = List.of(proof.get(0), proof.get(1), proof.get(2), prevote(4, 4, 1, block));
		final List<Ballot> swapped = List.of(proof.get(0), proof.get(1), prevote(4, 4, 1, block));

		assertThat(withProof(proved, List.of()).hasValidSignature(CHAIN), is(false));
		assertThat(withProof(proved, proof.subList(0, 2)).hasValidSignature(CHAIN), is(false));
		assertThat(withProof(proved, swapped).hasValidSignature(CHAIN), is(false));
		assertThat(withProof(proved, reordered).hasValidSignature(CHAIN), is(false));
		assertThat(withProof(proved, extended).hasValidSignature(CHAIN), is(false));
		assertThat(withProof(bare, proof).hasValidSignature(CHAIN), is(false));
	}

	@Example
	public void shouldNotBeEqualToAProposalWithAnotherProof() {
		final Block block = block(4, 0);
		final Proposal proved = Proposal.signed(seed(1), CHAIN, 4, 2, 1, block, prevotes(4, 1, block));
		final Proposal same = new Proposal(4, 2, 1, block, prevotes(4, 1, block), proved.sender(),
			proved.signature());
		final Proposal fewer = new Proposal(4, 2, 1, block, prevotes(4, 1, block).subList(0, 2), proved.sender(),
			proved.signature());

		assertThat(proved, equalTo(same));
		assertThat(proved.hashCode(), equalTo(same.hashCode()));
		assertThat(fewer, not(equalTo(proved)));
		assertThat(Proposal.signed(seed(1), CHAIN, 4, 2, 1, block), not(equalTo(proved)));
	}

	@Example
	public void shouldRefuseAProofBallotThatDoesNotProveThisBlockInTheValidRound() {
		final Block block = block(4, 0);
		final Ballot precommit = Ballot.signed(Ballot.Type.PRECOMMIT, seed(1), CHAIN, 4, 1, block.hash());

		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, 2, 1, block,
			List.of(precommit)));
		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, 2, 1, block,
			List.of(prevote(1, 5, 1, block))));
		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, 2, 1, block,
			List.of(prevote(1, 4, 0, block))));
		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, 2, 1, block,
			List.of(prevote(1, 4, 1, block(4, 1)))));
		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, 2, 1, block,
			List.of(prevote(1, 4, 1, block), prevote(2, 4, 1, block(4, 1)))));
	}

	@Example
	public void shouldRefuseAProofForANewBlock() {
		final Block block = block(4, 0);

		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, 2, -1, block,
			prevotes(4, 0, block)));
	}

	@Example
	public void shouldRefuseMoreProofBallotsThanThereCanBeValidators() {
		final Block block = block(4, 0);
		final Ballot ballot = prevote(1, 4, 1, block);

		assertThat(Proposal.signed(seed(1), CHAIN, 4, 2, 1, block,
			Collections.nCopies(ValidatorSet.MAX_SIZE, ballot)).polka().size(), is(ValidatorSet.MAX_SIZE));
		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, 2, 1, block,
			Collections.nCopies(ValidatorSet.MAX_SIZE + 1, ballot)));
	}

	@Example
	public void shouldKeepItsOwnCopyOfTheProof() {
		final Block block = block(4, 0);
		final List<Ballot> proof = new ArrayList<>(prevotes(4, 1, block));
		final Proposal proposal = Proposal.signed(seed(1), CHAIN, 4, 2, 1, block, proof);

		proof.clear();

		assertThat(proposal.polka().size(), is(3));
		assertThrows(UnsupportedOperationException.class, () -> proposal.polka().clear());
	}

	@Example
	public void shouldKeepAndHandOutCopiesOfTheSignature() {
		final Block block = block(4, 0);
		final Proposal signed = Proposal.signed(seed(1), CHAIN, 4, 2, 0, block);
		final byte[] signature = signed.signature();
		final Proposal proposal = new Proposal(4, 2, 0, block, List.of(), signed.sender(), signature);

		signature[0]++;
		proposal.signature()[0]++;

		assertThat(proposal.signature(), equalTo(signed.signature()));
		assertThat(proposal.hasValidSignature(CHAIN), is(true));
	}

	@Example
	public void shouldRefuseWhatIsNotAProposal() {
		final Block block = block(4, 0);

		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 5, 0, -1, block));
		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, -1, -1, block));
		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, 2, 2, block));
		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, 2, -2, block));
	}

	@Example
	public void shouldAllowTheViewOfTheValidRoundToBeAnyEarlierOne() {
		final Block block = block(4, 0);

		assertThat(Proposal.signed(seed(1), CHAIN, 4, 2, 1, block).validRound(), is(1));
		assertThat(Proposal.signed(seed(1), CHAIN, 4, 0, -1, block).validRound(), is(-1));
	}
}
