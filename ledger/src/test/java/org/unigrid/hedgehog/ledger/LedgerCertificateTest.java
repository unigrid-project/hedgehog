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
import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerCertificateTest {
	private static final List<byte[]> FOUR = seeds(1, 4);
	private static final List<byte[]> THREE = seeds(1, 3);

	private static String reasonOf(Optional<String> rejection) {
		return rejection.orElse("");
	}

	private static Block certified(LedgerState state, List<byte[]> seeds, int endorsers) {
		return block(state, seeds, endorsers, state.tipTime(), List.of(mint(1, 5)));
	}

	@Example
	public void shouldAcceptExactlyAQuorumOfFour() {
		final LedgerState state = state(1000, ANY_MINT, FOUR);

		assertThat(state.validators().quorum(), equalTo(3));
		assertThat(state.rejectionOf(certified(state, FOUR, 3)).isPresent(), is(false));
		assertThat(state.rejectionOf(certified(state, FOUR, 4)).isPresent(), is(false));
	}

	@Example
	public void shouldRefuseOneLessThanAQuorum() {
		final LedgerState state = state(1000, ANY_MINT, FOUR);

		assertThat(reasonOf(state.rejectionOf(certified(state, FOUR, 2))), containsString("quorum"));
		assertThat(reasonOf(state.rejectionOf(certified(state, FOUR, 1))), containsString("quorum"));
	}

	/* Two thirds exactly is not more than two thirds */
	@Example
	public void shouldRefuseTwoOfThree() {
		final LedgerState state = state(1000, ANY_MINT, THREE);

		assertThat(reasonOf(state.rejectionOf(certified(state, THREE, 2))), containsString("quorum"));
		assertThat(state.rejectionOf(certified(state, THREE, 3)).isPresent(), is(false));
	}

	@Example
	public void shouldRefuseTheSameValidatorCountedTwice() {
		final LedgerState state = state(1000, ANY_MINT, THREE);
		final Block good = certified(state, THREE, 3);
		final List<Endorsement> padded = new ArrayList<>(good.getEndorsements().subList(0, 2));

		padded.add(padded.get(1));
		assertThat(reasonOf(state.rejectionOf(good.toBuilder().endorsements(padded).build())),
			containsString("sorted"));
	}

	@Example
	public void shouldRefuseACertificateThatIsNotSorted() {
		final LedgerState state = state(1000, ANY_MINT, THREE);
		final Block good = certified(state, THREE, 3);
		final List<Endorsement> reversed = new ArrayList<>(good.getEndorsements());

		Collections.reverse(reversed);
		assertThat(reasonOf(state.rejectionOf(good.toBuilder().endorsements(reversed).build())),
			containsString("sorted"));
	}

	@Example
	public void shouldRefuseASignerOutsideTheSet() {
		final LedgerState state = state(1000, ANY_MINT, FOUR);
		final Block withOutsider = certified(state, FOUR, 3).endorsedBy(seed(50), state.chainId());

		assertThat(reasonOf(state.rejectionOf(withOutsider)), containsString("not a validator"));
	}

	/* The proposer of a block may have gone by the time a later view commits it */
	@Example
	public void shouldAcceptACertificateWithoutTheProposer() {
		final LedgerState state = state(1000, ANY_MINT, FOUR);
		final Block unsigned = certified(state, FOUR, 1).toBuilder().endorsements(List.of()).build();
		final Block withoutProposer = unsigned.endorsedBy(seed(2), state.chainId())
			.endorsedBy(seed(3), state.chainId()).endorsedBy(seed(4), state.chainId());

		assertThat(state.validators().proposerAt(1), equalTo(key(1)));
		assertThat(state.rejectionOf(withoutProposer).isPresent(), is(false));
	}

	@Example
	public void shouldAcceptACertificateMadeInALaterViewThanTheBlock() {
		final LedgerState state = state(1000, ANY_MINT, FOUR);
		Block later = certified(state, FOUR, 1).toBuilder().certificate(new Certificate(3, List.of())).build();

		for (final byte[] seed : FOUR) {
			later = later.endorsedBy(seed, state.chainId());
		}

		assertThat(later.getView(), equalTo(0));
		assertThat(state.rejectionOf(later).isPresent(), is(false));
	}

	private static Block proposedInViewAndCertifiedInView(LedgerState state, int blockView, int certificateView) {
		Block block = certified(state, FOUR, 1).toBuilder().view(blockView)
			.proposer(state.validators().proposerAt(1, blockView))
			.certificate(new Certificate(certificateView, List.of())).build();

		for (final byte[] seed : FOUR) {
			block = block.endorsedBy(seed, state.chainId());
		}

		return block;
	}

	/* A block proposed in one view can only be committed in that view or a later one */
	@Example
	public void shouldRefuseACertificateMadeInAnEarlierViewThanTheBlock() {
		final LedgerState state = state(1000, ANY_MINT, FOUR);
		final Block earlier = proposedInViewAndCertifiedInView(state, 2, 1);

		assertThat(reasonOf(state.rejectionOf(earlier)), containsString("earlier view"));
		assertThrows(InvalidBlockException.class, () -> state.apply(earlier));
	}

	/* A proposal has no signatures yet, so its certificate is empty and in view 0 whatever view it is made in */
	private static Block proposalOfView(LedgerState state, int view, AccountKey proposer) {
		return certified(state, FOUR, 1).toBuilder().view(view).proposer(proposer).endorsements(List.of())
			.certificate(Certificate.EMPTY).build();
	}

	@Example
	public void shouldAcceptAProposalOfALaterViewBeforeItHasAnySignatures() {
		final LedgerState state = state(1000, ANY_MINT, FOUR);
		final Block proposal = proposalOfView(state, 2, state.validators().proposerAt(1, 2));

		assertThat(proposal.getCertificate().view(), equalTo(0));
		assertThat(state.proposalRejection(proposal).isPresent(), is(false));
		assertThat(reasonOf(state.rejectionOf(proposal)), containsString("earlier view"));
	}

	@Example
	public void shouldRefuseAProposalFromTheWrongProposerOfItsView() {
		final LedgerState state = state(1000, ANY_MINT, FOUR);
		final AccountKey ofViewZero = state.validators().proposerAt(1, 0);
		final Block proposal = proposalOfView(state, 2, ofViewZero);

		assertThat(state.validators().proposerAt(1, 2), not(equalTo(ofViewZero)));
		assertThat(reasonOf(state.proposalRejection(proposal)), containsString("proposer"));
		assertThat(reasonOf(state.rejectionOf(proposal)), containsString("proposer"));
	}

	@Example
	public void shouldAcceptALaterViewCertificateForABlockProposedInViewOne() {
		final LedgerState state = state(1000, ANY_MINT, FOUR);
		final Block block = proposedInViewAndCertifiedInView(state, 1, 3);

		assertThat(block.getView(), equalTo(1));
		assertThat(block.getCertificate().view(), equalTo(3));
		assertThat(state.rejectionOf(block).isPresent(), is(false));
	}

	@Example
	public void shouldRefuseEndorsementsMadeInAnotherViewThanTheCertificateNames() {
		final LedgerState state = state(1000, ANY_MINT, THREE);
		final Block good = certified(state, THREE, 3);
		final Block renamed = good.toBuilder().certificate(new Certificate(1, good.getEndorsements())).build();

		assertThat(reasonOf(state.rejectionOf(renamed)), containsString("signature"));
	}

	@Example
	public void shouldTakeTheProposerOfTheViewOfTheBlock() {
		final LedgerState state = state(1000, ANY_MINT, FOUR);
		final Block base = certified(state, FOUR, 1);
		final Block unsigned = base.toBuilder().view(1).proposer(state.validators().proposerAt(1, 1))
			.certificate(new Certificate(1, List.of())).build();
		final Block wrongProposer = unsigned.toBuilder().proposer(base.getProposer()).build();
		Block inViewOne = unsigned;
		Block inViewOneByTheFirst = wrongProposer;

		for (final byte[] seed : FOUR) {
			inViewOne = inViewOne.endorsedBy(seed, state.chainId());
			inViewOneByTheFirst = inViewOneByTheFirst.endorsedBy(seed, state.chainId());
		}

		assertThat(state.rejectionOf(inViewOne).isPresent(), is(false));
		assertThat(reasonOf(state.rejectionOf(inViewOneByTheFirst)), containsString("proposer"));
	}

	/* Signatures made for another block must not carry over, however valid they are for that block */
	@Example
	public void shouldRefuseSignaturesMadeForAnotherBlock() {
		final LedgerState state = state(1000, ANY_MINT, THREE);
		final Block other = block(state, THREE, 3, state.tipTime(), List.of(mint(2, 9)));
		final Block mine = certified(state, THREE, 3).toBuilder().endorsements(other.getEndorsements()).build();

		assertThat(reasonOf(state.rejectionOf(mine)), containsString("signature"));
	}

	@Example
	public void shouldRefuseACorruptedSignature() {
		final LedgerState state = state(1000, ANY_MINT, THREE);
		final Block good = certified(state, THREE, 3);
		final List<Endorsement> corrupted = new ArrayList<>(good.getEndorsements());
		final byte[] signature = corrupted.get(1).signature();

		signature[0] ^= 1;
		corrupted.set(1, new Endorsement(corrupted.get(1).signer(), signature));
		assertThat(reasonOf(state.rejectionOf(good.toBuilder().endorsements(corrupted).build())),
			containsString("signature"));
	}

	/* Anyone can send a block, so the cheap proof that validators signed it comes before the work its
	   transactions cost; here the block also holds a transaction that is wrong, and the signatures are what is named */
	@Example
	public void shouldCheckTheSignaturesBeforeLookingAtTheTransactions() {
		final LedgerState state = state(1000, ANY_MINT, THREE);

		state.apply(certified(state, THREE, 3));

		final List<Transaction> reused = List.of(new Mint(account(2), 9, reference(1)));
		final Block other = block(state, THREE, 3, state.tipTime(), List.of(mint(2, 9)));
		final Block forged = other.toBuilder().transactions(reused).transactionRoot(Block.transactionRootOf(reused))
			.build();

		assertThat(reasonOf(state.rejectionOf(forged)), containsString("signature"));
	}

	@Example
	public void shouldLeaveTheStateAloneWhenTheCertificateIsRefused() {
		final LedgerState state = state(1000, ANY_MINT, THREE);
		final byte[] root = state.stateRoot();
		final Block short2 = certified(state, THREE, 2);

		assertThrows(InvalidBlockException.class, () -> state.apply(short2));
		assertThat(state.height(), equalTo(0L));
		assertThat(state.stateRoot(), equalTo(root));
		assertThat(state.balanceOf(account(1)), equalTo(0L));
	}
}
