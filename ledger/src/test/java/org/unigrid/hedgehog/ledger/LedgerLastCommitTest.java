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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerLastCommitTest {
	private static final List<byte[]> FOUR = seeds(1, 4);
	private static final List<byte[]> THREE = seeds(1, 3);
	private static final List<byte[]> FIVE = Stream.concat(FOUR.stream(), Stream.of(seed(200))).toList();
	private static final long ROUND = 60_000;

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

	private static Certificate certificateOfAnotherFirstBlock() {
		return block(state(1000, ANY_MINT, THREE), THREE, List.of(mint(9, 5))).getCertificate();
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

	/* Signatures for another block at the same height, or the same signatures presented in another view, commit
	   nothing here */
	@Example
	public void shouldRefuseALastCommitSignedForAnotherBlock() {
		final LedgerState state = afterOneBlock(THREE);
		final Block forged = nextWith(state, THREE, certificateOfAnotherFirstBlock());
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

	/* Four validators, three of whom vote a gridnode in, and the block of the next round that closes theirs: it has
	   a certificate of 3 and leaves a set of 5 behind */
	private static LedgerState afterTheSetGrows() {
		final LedgerState state = state(ROUND, ANY_MINT, FOUR);
		final AccountKey gridnode = key(200);
		final List<Transaction> votes = List.of(
			Vote.signed(seed(1), gridnode, Vote.Action.ADD, state.round(), state.chainId()),
			Vote.signed(seed(2), gridnode, Vote.Action.ADD, state.round(), state.chainId()),
			Vote.signed(seed(3), gridnode, Vote.Action.ADD, state.round(), state.chainId()));

		state.apply(block(state, FOUR, 3, state.tipTime(), votes));
		state.apply(block(state, FOUR, 3, GENESIS_TIME + ROUND, List.of(mint(1, 5))));
		return state;
	}

	private static Ledger ledgerOf(FileSystem fileSystem, LongSupplier clock) throws IOException {
		return new Ledger(state(ROUND, ANY_MINT, FOUR), BlockLog.open(fileSystem.getPath("/data/blocks.log")),
			clock);
	}

	@Example
	public void shouldRefuseToSignABlockWithoutTheLastCommit() {
		final LedgerState state = afterOneBlock(THREE);
		final Block missing = nextWith(state, THREE, Certificate.EMPTY);

		assertThat(state.proposalRejection(next(state, THREE)).isPresent(), is(false));
		assertThat(reasonOf(state.proposalRejection(missing)), containsString("quorum"));
	}

	@Example
	public void shouldRefuseToSignABlockWhoseLastCommitIsForAnotherBlock() {
		final LedgerState state = afterOneBlock(THREE);
		final Block forged = nextWith(state, THREE, certificateOfAnotherFirstBlock());
		final Block otherView = nextWith(state, THREE, new Certificate(1, state.tipCertificate().endorsements()));

		assertThat(reasonOf(state.proposalRejection(forged)), containsString("previous block"));
		assertThat(reasonOf(state.proposalRejection(otherView)), containsString("previous block"));
	}

	@Example
	public void shouldProposeTheLastCommitOfTheSetBeforeTheChange() {
		final LedgerState state = afterTheSetGrows();
		final Block second = block(state, FOUR, 4, state.tipTime(), List.of(mint(2, 5)));

		assertThat(state.validators().quorum(), equalTo(4));
		assertThat(second.getLastCommit().endorsements().size(), equalTo(3));
		assertThat(state.proposalRejection(second).isPresent(), is(false));
	}

	@Example
	public void shouldValidateWithTheLastCommitOfTheSetBeforeTheChange() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(2000);
			final Ledger ledger = ledgerOf(fileSystem, clock::get);

			for (int voter = 1; voter <= 3; voter++) {
				ledger.submit(Vote.signed(seed(voter), key(200), Vote.Action.ADD, 0, ledger.chainId()));
			}

			ledger.commit(nextBlock(ledger, THREE, 2000));
			clock.set(GENESIS_TIME + ROUND);
			ledger.submit(mint(1, 5));
			ledger.commit(nextBlock(ledger, THREE, clock.get()));
			ledger.submit(mint(2, 5));

			final Block second = proposed(ledger, clock.get());

			assertThat(ledger.validators().size(), equalTo(5));
			assertThat(second.getLastCommit().endorsements().size(), equalTo(3));
			assertThat(ledger.validate(second), equalTo(Optional.empty()));
		}
	}

	/* The block that ends a round may change the set; the next block still carries the commit of the old set,
	   which can be smaller than the quorum of the new one */
	@Example
	public void shouldCheckTheLastCommitAgainstTheSetThatSignedIt() {
		final LedgerState state = afterTheSetGrows();

		assertThat(state.validators().size(), equalTo(5));
		assertThat(state.previousValidators().size(), equalTo(4));
		assertThat(state.tipCertificate().endorsements().size(), equalTo(3));
		assertThat(state.validators().quorum(), equalTo(4));

		final Block second = block(state, FOUR, 4, state.tipTime(), List.of(mint(2, 5)));

		assertThat(second.getLastCommit().endorsements().size(), equalTo(3));
		assertThat(state.rejectionOf(second).isPresent(), is(false));
	}

	/* The gridnode joins after the first round, the second round votes it out again, and the block that closes that
	   round is one the gridnode signs */
	private static LedgerState afterTheGridnodeIsVotedOut() {
		final LedgerState state = afterTheSetGrows();
		final List<Transaction> removals = FOUR.stream().<Transaction>map(voter -> Vote.signed(voter, key(200),
			Vote.Action.REMOVE, state.round(), state.chainId())).toList();

		state.apply(block(state, FIVE, removals));
		state.apply(block(state, FIVE, FIVE.size(), GENESIS_TIME + 2 * ROUND, List.of(mint(3, 5))));
		return state;
	}

	@Example
	public void shouldRefuseALastCommitSignedByAValidatorThatJoinedWhenTheRoundEnded() {
		final LedgerState state = afterTheSetGrows();
		final List<Endorsement> joined = new ArrayList<>(state.tipCertificate().endorsements());

		joined.add(new Endorsement(key(200),
			Precommit.sign(seed(200), state.chainId(), state.height(), 0, state.tipHash())));
		joined.sort(Comparator.comparing(Endorsement::signer));

		final Block block = nextWith(state, FIVE, new Certificate(0, joined));

		assertThat(state.validators().contains(key(200)), is(true));
		assertThat(reasonOf(state.rejectionOf(block)), containsString("not a validator"));
	}

	@Example
	public void shouldAcceptALastCommitSignedByAValidatorThatWasRemovedWhenTheRoundEnded() {
		final LedgerState state = afterTheGridnodeIsVotedOut();

		assertThat(state.validators().contains(key(200)), is(false));
		assertThat(state.previousValidators().contains(key(200)), is(true));
		assertThat(state.tipCertificate().endorsements().stream().anyMatch(e -> e.signer().equals(key(200))),
			is(true));
		assertThat(state.rejectionOf(next(state, FIVE)).isPresent(), is(false));
	}

	@Example
	public void shouldRefuseALastCommitBelowTheQuorumOfTheSetThatSignedIt() {
		final LedgerState state = afterTheSetGrows();
		final List<Endorsement> two = state.tipCertificate().endorsements().subList(0, 2);
		final Block thin = nextWith(state, FIVE, new Certificate(0, two));

		assertThat(state.previousValidators().quorum(), equalTo(3));
		assertThat(reasonOf(state.rejectionOf(thin)), containsString("quorum"));
	}
}
