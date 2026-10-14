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
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerLastCommitTest {
	private static final List<byte[]> FOUR = seeds(1, 4);
	private static final List<byte[]> THREE = seeds(1, 3);

	private static String reasonOf(Optional<String> rejection) {
		return rejection.orElse("");
	}

	private static LedgerState afterOneBlock(List<byte[]> seeds) {
		final LedgerState state = state(1000, ANY_MINT, seeds);

		state.apply(block(state, seeds, List.of(mint(1, 5))));
		return state;
	}

	private static Block next(LedgerState state, List<byte[]> seeds) {
		return block(state, seeds, List.of(mint(2, 5)));
	}

	/* The next block with another last commit, signed again by every validator since the commit is in its hash */
	private static Block nextWith(LedgerState state, List<byte[]> seeds, Certificate lastCommit) {
		Block signed = next(state, seeds).toBuilder().lastCommit(lastCommit).certificate(Certificate.EMPTY).build();

		for (final byte[] seed : seeds) {
			signed = signed.endorsedBy(seed, state.chainId());
		}

		return signed;
	}

	@Example
	public void shouldHaveNoLastCommitOnTheFirstBlock() {
		final LedgerState state = state(1000, ANY_MINT, THREE);
		final Block first = block(state, THREE, List.of(mint(1, 5)));
		final Block withOne = nextWith(state, THREE, new Certificate(0, first.getEndorsements()));

		assertThat(first.getLastCommit(), equalTo(Certificate.EMPTY));
		assertThat(state.rejectionOf(first).isPresent(), is(false));
		assertThat(reasonOf(state.rejectionOf(withOne)), containsString("first block"));
	}

	@Example
	public void shouldKeepTheCertificateOfTheTip() {
		final LedgerState state = afterOneBlock(THREE);

		assertThat(state.tipCertificate().endorsements().size(), equalTo(3));
		assertThat(state.tipCertificate().endorsements().stream().anyMatch(e -> e.signer().equals(key(1))),
			is(true));
	}

	@Example
	public void shouldAcceptTheCertificateOfThePreviousBlockAsLastCommit() {
		final LedgerState state = afterOneBlock(THREE);

		assertThat(next(state, THREE).getLastCommit(), equalTo(state.tipCertificate()));
		assertThat(state.rejectionOf(next(state, THREE)).isPresent(), is(false));
	}

	@Example
	public void shouldRefuseAMissingLastCommit() {
		final LedgerState state = afterOneBlock(THREE);
		final Block missing = nextWith(state, THREE, Certificate.EMPTY);

		assertThat(reasonOf(state.rejectionOf(missing)), containsString("quorum"));
	}

	@Example
	public void shouldRefuseALastCommitThatIsNotSorted() {
		final LedgerState state = afterOneBlock(THREE);
		final List<Endorsement> reversed = new ArrayList<>(state.tipCertificate().endorsements());

		Collections.reverse(reversed);

		final Block unsorted = nextWith(state, THREE, new Certificate(0, reversed));

		assertThat(reasonOf(state.rejectionOf(unsorted)), containsString("sorted"));
	}

	@Example
	public void shouldRefuseASignerOutsideTheSet() {
		final LedgerState state = afterOneBlock(THREE);
		final List<Endorsement> padded = new ArrayList<>(state.tipCertificate().endorsements());

		padded.add(new Endorsement(key(50), new byte[Ed25519.SIGNATURE_SIZE]));

		final Block outsider = nextWith(state, THREE, new Certificate(0, padded));

		assertThat(reasonOf(state.rejectionOf(outsider)), containsString("not a validator"));
	}

	/* Signatures for the block before the previous one, or made in another view, commit nothing here */
	@Example
	public void shouldRefuseALastCommitSignedForAnotherBlock() {
		final LedgerState state = afterOneBlock(THREE);
		final Block other = block(state, THREE, List.of(mint(9, 5)));
		final Block forged = nextWith(state, THREE, other.getCertificate());
		final Block otherView = nextWith(state, THREE, new Certificate(1, state.tipCertificate().endorsements()));

		assertThat(reasonOf(state.rejectionOf(forged)), containsString("previous block"));
		assertThat(reasonOf(state.rejectionOf(otherView)), containsString("previous block"));
	}

	@Example
	public void shouldNotLetASignerOfTheLastCommitSignTwice() {
		final LedgerState state = afterOneBlock(THREE);
		final List<Endorsement> padded = new ArrayList<>(state.tipCertificate().endorsements());

		padded.add(padded.get(2));

		final Block twice = nextWith(state, THREE, new Certificate(0, padded));

		assertThat(reasonOf(state.rejectionOf(twice)), containsString("sorted"));
	}

	@Example
	public void shouldBindTheHashOfTheBlockToItsLastCommit() {
		final LedgerState state = afterOneBlock(THREE);
		final Block block = next(state, THREE);
		final Block changed = block.toBuilder().lastCommit(new Certificate(1, block.getLastCommit().endorsements()))
			.build();

		assertThat(block.getLastCommitHash(), equalTo(block.getLastCommit().hash()));
		assertThat(changed.hash(), org.hamcrest.Matchers.not(equalTo(block.hash())));
	}

	/* The block that ends a round may change the set; the next block still carries the commit of the old set,
	   which can be smaller than the quorum of the new one */
	@Example
	public void shouldCheckTheLastCommitAgainstTheSetThatSignedIt() {
		final LedgerState state = state(1, ANY_MINT, FOUR);
		final AccountKey gridnode = key(200);
		final List<Transaction> votes = List.of(
			Vote.signed(seed(1), gridnode, Vote.Action.ADD, state.round(), state.chainId()),
			Vote.signed(seed(2), gridnode, Vote.Action.ADD, state.round(), state.chainId()),
			Vote.signed(seed(3), gridnode, Vote.Action.ADD, state.round(), state.chainId()));

		state.apply(block(state, FOUR, 3, state.tipTime(), votes));

		assertThat(state.validators().size(), equalTo(5));
		assertThat(state.previousValidators().size(), equalTo(4));
		assertThat(state.tipCertificate().endorsements().size(), equalTo(3));
		assertThat(state.validators().quorum(), equalTo(4));

		final Block second = block(state, FOUR, 4, state.tipTime(), List.of(mint(2, 5)));

		assertThat(second.getLastCommit().endorsements().size(), equalTo(3));
		assertThat(state.rejectionOf(second).isPresent(), is(false));
	}
}
