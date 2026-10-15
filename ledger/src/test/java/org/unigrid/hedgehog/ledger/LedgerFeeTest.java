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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerFeeTest {
	private static final List<byte[]> FOUR = seeds(1, 4);

	/* Four validators need three signatures, so a block can be signed by three of them and leave one out */
	private static LedgerState chain() {
		return state(1000, ANY_MINT, FOUR);
	}

	private static void commit(LedgerState state, Transaction... transactions) {
		commitSignedBy(state, 3, transactions);
	}

	private static void commitSignedBy(LedgerState state, int signers, Transaction... transactions) {
		state.apply(block(state, FOUR, signers, state.tipTime(), Arrays.asList(transactions)));
	}

	/* A block's hash leaves its certificate out, so the same tip can be committed by three validators on one chain
	   and by all four on another, and both chains hold the same state */
	private static LedgerState chainWithFeesWaitingSignedBy(int signers) {
		final LedgerState state = chain();

		commit(state, mint(1, 1000));
		commitSignedBy(state, signers, transfer(state, 1, account(2), 300, 10, 0));
		return state;
	}

	private static Block signedByThree(Block block, byte[] chainId) {
		Block signed = block.toBuilder().certificate(Certificate.EMPTY).build();

		for (final byte[] seed : FOUR.subList(0, 3)) {
			signed = signed.endorsedBy(seed, chainId);
		}

		return signed;
	}

	private static List<AccountKey> signersOfTheTip(LedgerState state) {
		return state.tipCertificate().endorsements().stream().map(Endorsement::signer).toList();
	}

	private static byte[] seedOf(AccountKey key) {
		return FOUR.stream().filter(seed -> Ed25519.publicKey(seed).equals(key)).findFirst().orElseThrow();
	}

	private static String reasonOf(Optional<String> rejection) {
		return rejection.orElse("");
	}

	@Example
	public void shouldPayTheFeesOfABlockToTheSignersOfItInTheNextBlock() {
		final LedgerState state = chain();

		commit(state, mint(1, 1000));
		commit(state, transfer(state, 1, account(2), 300, 10, 0));

		final List<AccountKey> signers = signersOfTheTip(state);
		final AccountKey absent = state.validators().keys().stream().filter(key -> !signers.contains(key))
			.findFirst().orElseThrow();

		commit(state);

		assertThat(state.balanceOf(signers.get(0)), equalTo(4L));
		assertThat(state.balanceOf(signers.get(1)), equalTo(3L));
		assertThat(state.balanceOf(signers.get(2)), equalTo(3L));
		assertThat(state.balanceOf(absent), equalTo(0L));
		assertThat(state.pool(), equalTo(0L));
		assertThat(state.supply(), equalTo(1000L));
	}

	@Example
	public void shouldGiveTheRemainderToTheFirstSignersInKeyOrder() {
		final LedgerState state = chain();

		commit(state, mint(1, 1000));
		commit(state, transfer(state, 1, account(2), 300, 2, 0));

		final List<AccountKey> signers = signersOfTheTip(state);

		commit(state);

		assertThat(state.balanceOf(signers.get(0)), equalTo(1L));
		assertThat(state.balanceOf(signers.get(1)), equalTo(1L));
		assertThat(state.balanceOf(signers.get(2)), equalTo(0L));
	}

	@Example
	public void shouldAllowAnEmptyBlockOnlyWhileFeesWait() {
		final LedgerState state = chain();

		commit(state, mint(1, 1000));
		assertThat(reasonOf(state.rejectionOf(block(state, FOUR, 3, state.tipTime(), List.of()))),
			containsString("1 to " + Block.MAX_TRANSACTIONS));

		commit(state, transfer(state, 1, account(2), 300, 10, 0));
		assertThat(state.rejectionOf(block(state, FOUR, 3, state.tipTime(), List.of())).isPresent(), is(false));

		commit(state);
		assertThat(state.pool(), equalTo(0L));
		assertThat(reasonOf(state.rejectionOf(block(state, FOUR, 3, state.tipTime(), List.of()))),
			containsString("1 to " + Block.MAX_TRANSACTIONS));
	}

	@Example
	public void shouldLetASignerSpendWhatItIsPaidInTheSameBlock() {
		final LedgerState state = chain();

		commit(state, mint(1, 1000));
		commit(state, transfer(state, 1, account(2), 300, 10, 0));

		final AccountKey first = signersOfTheTip(state).get(0);
		final Transfer spend = Transfer.signed(seedOf(first), account(3), 3, 0, 0, state.chainId());

		assertThat(state.applicable(List.of(spend)), equalTo(List.of(spend)));

		commit(state, spend);

		assertThat(state.balanceOf(first), equalTo(1L));
		assertThat(state.balanceOf(account(3)), equalTo(3L));
	}

	@Example
	public void shouldProposeAnEmptyBlockWhileFeesWaitAndNotAfterwards() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = new Ledger(chain(), BlockLog.open(fileSystem.getPath("/data/blocks.log")));

			ledger.submit(mint(1, 1000));
			ledger.commit(nextBlock(ledger, FOUR, 2000));
			ledger.submit(paying(ledger, 300, 10, 0));
			ledger.commit(nextBlock(ledger, FOUR, 2000));

			final Block empty = nextBlock(ledger, FOUR, 2000);

			assertThat(empty.getTransactions().isEmpty(), is(true));
			ledger.commit(empty);
			assertThat(proposal(ledger, 2000), equalTo(Optional.empty()));
			assertThat(ledger.supply(), equalTo(1000L));
		}
	}

	/* The tip was committed by three validators, but the fourth signed it too, and a block may carry any valid
	   commit of its predecessor: what is paid is what the block names, not what this node stored */
	@Example
	public void shouldPayTheSignersOfTheLastCommitTheBlockCarries() {
		final LedgerState state = chainWithFeesWaitingSignedBy(3);
		final LedgerState wide = chainWithFeesWaitingSignedBy(4);
		final Block block = block(wide, FOUR, 3, wide.tipTime(), List.of());
		final List<AccountKey> signers = block.getLastCommit().endorsements().stream().map(Endorsement::signer)
			.toList();

		assertThat(wide.tipHash(), equalTo(state.tipHash()));
		assertThat(wide.stateRoot(), equalTo(state.stateRoot()));
		assertThat(state.tipCertificate().endorsements().size(), equalTo(3));
		assertThat(signers.size(), equalTo(4));
		assertThat(state.rejectionOf(block).isPresent(), is(false));
		assertThat(state.proposalRejection(block).isPresent(), is(false));

		state.apply(block);

		assertThat(signers.stream().map(state::balanceOf).toList(), equalTo(List.of(3L, 3L, 2L, 2L)));
		assertThat(state.pool(), equalTo(0L));
		assertThat(state.supply(), equalTo(1000L));
	}

	@Example
	public void shouldRefuseABlockWhoseRootWasComputedForAnotherLastCommit() {
		final LedgerState state = chainWithFeesWaitingSignedBy(3);
		final LedgerState wide = chainWithFeesWaitingSignedBy(4);
		final Block rootForFour = block(wide, FOUR, 3, wide.tipTime(), List.of());
		final Block carryingThree = signedByThree(rootForFour.toBuilder().lastCommit(state.tipCertificate())
			.build(), state.chainId());

		assertThat(carryingThree.getLastCommit(), equalTo(state.tipCertificate()));
		assertThat(reasonOf(state.rejectionOf(carryingThree)), containsString("State root does not match"));
		assertThat(state.rejectionOf(block(state, FOUR, 3, state.tipTime(), List.of())).isPresent(), is(false));
	}

	@Example
	public void shouldPayNothingWhenNoFeeWaits() {
		final LedgerState state = chain();

		commit(state, mint(1, 1000));
		commit(state, mint(2, 5));

		state.tipCertificate().endorsements().forEach(endorsement ->
			assertThat(state.balanceOf(endorsement.signer()), equalTo(0L)));
	}
}
