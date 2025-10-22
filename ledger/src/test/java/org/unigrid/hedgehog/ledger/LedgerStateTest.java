/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerStateTest {
	private static String reasonOf(Optional<String> rejection) {
		return rejection.orElse("");
	}

	@Example
	public void shouldStartAtTheGenesis() {
		final LedgerState state = soloState(ANY_MINT);

		assertThat(state.height(), equalTo(0L));
		assertThat(state.tipHash(), equalTo(state.chainId()));
		assertThat(state.tipTime(), equalTo(1000L));
		assertThat(state.round(), equalTo(0L));
		assertThat(state.validators(), equalTo(genesis(1000, seeds(1, 1)).getValidators()));
		assertThat(state.balanceOf(account(1)), equalTo(0L));
		assertThat(state.hasMinted(reference(1)), is(false));
	}

	@Example
	public void shouldCreditAMintOnce() {
		final LedgerState state = soloState(ANY_MINT);
		final byte[] before = state.stateRoot();
		final Block block = soloBlock(state, mint(1, 500));

		state.apply(block);

		assertThat(state.height(), equalTo(1L));
		assertThat(state.balanceOf(account(1)), equalTo(500L));
		assertThat(state.hasMinted(reference(1)), is(true));
		assertThat(state.tipHash(), equalTo(block.hash()));
		assertThat(state.stateRoot(), not(equalTo(before)));
	}

	@Example
	public void shouldAddUpMintsToTheSameAccount() {
		final LedgerState state = soloState(ANY_MINT);
		final Mint first = new Mint(account(1), 5, reference(1));
		final Mint second = new Mint(account(1), 7, reference(2));

		state.apply(soloBlock(state, first, second));
		assertThat(state.balanceOf(account(1)), equalTo(12L));
	}

	@Example
	public void shouldRefuseAReferenceThatWasUsed() {
		final LedgerState state = soloState(ANY_MINT);

		state.apply(soloBlock(state, mint(1, 500)));

		assertThat(reasonOf(state.rejectionOf(new Mint(account(2), 9, reference(1)))),
			containsString("Reference already used"));
	}

	@Example
	public void shouldRefuseTheSameReferenceTwiceInOneBlock() {
		final LedgerState state = soloState(ANY_MINT);
		final List<Transaction> both = List.of(new Mint(account(1), 5, reference(1)),
			new Mint(account(2), 5, reference(1)));

		assertThrows(IllegalArgumentException.class, () -> state.rootAfter(1, both));
	}

	@Example
	public void shouldRefuseAMintTheAuthorityDoesNotAllow() {
		final LedgerState state = soloState(NO_MINT);

		assertThat(reasonOf(state.rejectionOf(mint(1, 5))), containsString("not authorized"));
	}

	@Example
	public void shouldAskTheAuthorityAboutEachMint() {
		final Mint allowed = mint(1, 5);
		final LedgerState state = soloState(mint -> mint.equals(allowed));

		assertThat(state.rejectionOf(allowed).isPresent(), is(false));
		assertThat(state.rejectionOf(mint(2, 5)).isPresent(), is(true));
	}

	/* More than 2/3 of the validators signed it after asking the authority, so a node whose authority does not
	   know the mint yet, or no longer does, takes the block all the same instead of disagreeing with its peers */
	@Example
	public void shouldTakeACertifiedBlockWhateverTheLocalAuthoritySays() {
		final LedgerState signer = soloState(ANY_MINT);
		final Block certified = soloBlock(signer, mint(1, 500));
		final LedgerState follower = soloState(NO_MINT);

		follower.apply(certified);

		assertThat(follower.balanceOf(account(1)), equalTo(500L));
		assertThat(follower.stateRoot(), equalTo(signer.rootAfter(1, List.of(mint(1, 500)))));
	}

	/* A validator still asks the authority before it signs, and a proposer before it proposes */
	@Example
	public void shouldStillAskTheAuthorityBeforeSigning() {
		final LedgerState signer = soloState(ANY_MINT);
		final Block proposal = soloBlock(signer, mint(1, 500));
		final LedgerState cautious = soloState(NO_MINT);

		assertThat(reasonOf(cautious.proposalRejection(proposal)), containsString("not authorized"));
		assertThat(signer.proposalRejection(proposal).isPresent(), is(false));
	}

	@Example
	public void shouldNeverLetABalanceWrap() {
		final LedgerState state = soloState(ANY_MINT);

		state.apply(soloBlock(state, new Mint(account(1), Long.MAX_VALUE, reference(1))));

		assertThat(reasonOf(state.rejectionOf(new Mint(account(1), 1, reference(2)))), containsString("overflow"));
		assertThat(state.rejectionOf(new Mint(account(2), 1, reference(2))).isPresent(), is(false));
	}

	@Example
	public void shouldRefuseABlockWithAWrongHeader() {
		final LedgerState state = soloState(ANY_MINT);
		final Block good = soloBlock(state, mint(1, 5));

		assertThat(state.rejectionOf(good).isPresent(), is(false));
		assertThat(state.rejectionOf(good.toBuilder().height(2).build()).isPresent(), is(true));
		assertThat(state.rejectionOf(good.toBuilder().previousHash(new byte[Digests.HASH_SIZE]).build()).isPresent(),
			is(true));
		assertThat(state.rejectionOf(good.toBuilder().time(999).build()).isPresent(), is(true));
		assertThat(state.rejectionOf(good.toBuilder().stateRoot(new byte[Digests.HASH_SIZE]).build()).isPresent(),
			is(true));
		assertThat(state.rejectionOf(good.toBuilder().transactionRoot(new byte[Digests.HASH_SIZE]).build())
			.isPresent(), is(true));
	}

	/* The transactions are bound to the header only through the transaction root, so a block carrying other
	   transactions than the root commits to must be refused even when everything else is intact */
	@Example
	public void shouldRefuseABlockWhoseTransactionsAreNotTheOnesItCommitsTo() {
		final LedgerState state = soloState(ANY_MINT);
		final Block good = soloBlock(state, mint(1, 5));
		final Block swapped = good.toBuilder().transactions(List.of(mint(1, 6))).build();

		assertThat(reasonOf(state.rejectionOf(swapped)), containsString("Transaction root"));
	}

	@Example
	public void shouldRefuseABlockFromAnyoneButTheProposerOfItsHeight() {
		final List<byte[]> seeds = seeds(1, 2);
		final LedgerState state = state(1000, ANY_MINT, seeds);
		final Block good = block(state, seeds, List.of(mint(1, 5)));
		final Block wrongProposer = good.toBuilder().proposer(Ed25519.publicKey(seeds.get(1))).build();

		assertThat(state.rejectionOf(good).isPresent(), is(false));
		assertThat(reasonOf(state.rejectionOf(wrongProposer)), containsString("proposer"));
	}

	@Example
	public void shouldRefuseAnEmptyBlockAndABlockOverTheMaximum() {
		final LedgerState state = soloState(ANY_MINT);
		final Block empty = soloBlock(state);
		final List<Transaction> many = Collections.nCopies(Block.MAX_TRANSACTIONS + 1, mint(1, 5));
		final Block oversized = empty.toBuilder().transactions(many).transactionRoot(Block.transactionRootOf(many))
			.build();

		assertThat(reasonOf(state.rejectionOf(empty)), containsString("1 to " + Block.MAX_TRANSACTIONS));
		assertThat(reasonOf(state.rejectionOf(oversized)), containsString("1 to " + Block.MAX_TRANSACTIONS));
	}

	@Example
	public void shouldLeaveTheStateAloneWhenABlockIsRefused() {
		final LedgerState state = soloState(ANY_MINT);
		final byte[] root = state.stateRoot();
		final Block bad = soloBlock(state, mint(1, 5)).toBuilder().height(2).build();

		assertThrows(InvalidBlockException.class, () -> state.apply(bad));
		assertThat(state.height(), equalTo(0L));
		assertThat(state.stateRoot(), equalTo(root));
		assertThat(state.tipHash(), equalTo(state.chainId()));
		assertThat(state.balanceOf(account(1)), equalTo(0L));
	}

	@Example
	public void shouldChainBlocksAndKeepTheTimeFromGoingBack() {
		final LedgerState state = soloState(ANY_MINT);

		state.apply(block(state, seeds(1, 1), 1, 2000, List.of(mint(1, 5))));
		assertThat(state.tipTime(), equalTo(2000L));
		state.apply(block(state, seeds(1, 1), 1, 2000, List.of(mint(2, 5))));
		assertThat(state.height(), equalTo(2L));
		assertThat(state.rejectionOf(block(state, seeds(1, 1), 1, 1999, List.of(mint(3, 5)))).isPresent(), is(true));
	}

	@Example
	public void shouldGiveTheSameRootToTheSameHistory() {
		final LedgerState one = soloState(ANY_MINT);
		final LedgerState two = soloState(ANY_MINT);
		final Block block = soloBlock(one, mint(1, 5));

		one.apply(block);
		two.apply(block);
		assertThat(one.stateRoot(), equalTo(two.stateRoot()));
		assertThat(one.tipHash(), equalTo(two.tipHash()));
	}

	@Example
	public void shouldNotDependOnTheOrderOfMintsInTheRoot() {
		final LedgerState state = soloState(ANY_MINT);

		assertThat(state.rootAfter(1, List.of(mint(1, 5), mint(2, 6))), equalTo(state.rootAfter(1,
			List.of(mint(2, 6), mint(1, 5)))));
	}

	@Example
	public void shouldCommitTheRootToTheRecipientAndTheReference() {
		final LedgerState state = soloState(ANY_MINT);
		final byte[] base = state.rootAfter(1, List.of(new Mint(account(1), 5, reference(1))));

		assertThat(state.rootAfter(1, List.of(new Mint(account(2), 5, reference(1)))), not(equalTo(base)));
		assertThat(state.rootAfter(1, List.of(new Mint(account(1), 6, reference(1)))), not(equalTo(base)));
		assertThat(state.rootAfter(1, List.of(new Mint(account(1), 5, reference(2)))), not(equalTo(base)));
	}

	@Property(tries = 30)
	public void shouldHoldExactlyWhatWasMinted(@ForAll @IntRange(min = 1, max = 20) int mints,
		@ForAll @IntRange(min = 1, max = 4) int accounts) {
		final LedgerState state = soloState(ANY_MINT);
		final List<Transaction> transactions = IntStream.range(0, mints)
			.mapToObj(i -> (Transaction) new Mint(account(i % accounts), i + 1L, reference(i))).toList();

		state.apply(soloBlock(state, transactions.toArray(new Transaction[0])));

		assertThat(IntStream.range(0, accounts).mapToLong(i -> state.balanceOf(account(i))).sum(),
			equalTo(IntStream.range(0, mints).mapToLong(i -> i + 1L).sum()));
	}
}
