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
		final Block withOutsider = certified(state, FOUR, 3).endorsedBy(seed(50));

		assertThat(reasonOf(state.rejectionOf(withOutsider)), containsString("not a validator"));
	}

	@Example
	public void shouldRefuseACertificateWithoutTheProposer() {
		final LedgerState state = state(1000, ANY_MINT, FOUR);
		final Block unsigned = certified(state, FOUR, 1).toBuilder().endorsements(List.of()).build();
		final Block withoutProposer = unsigned.endorsedBy(seed(2)).endorsedBy(seed(3)).endorsedBy(seed(4));

		assertThat(state.validators().proposerAt(1), equalTo(key(1)));
		assertThat(reasonOf(state.rejectionOf(withoutProposer)), containsString("proposer"));
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
