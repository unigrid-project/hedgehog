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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerAuthorityTest {
	private static String reasonOf(Optional<String> rejection) {
		return rejection.orElse("");
	}

	@Example
	public void shouldAskTheAuthorityAboutTheTimeItWasToldToAskAt() {
		final LedgerState state = soloState(mintsWhen((mint, time) -> time >= 5000));

		state.askAuthorityAt(4999);
		assertThat(reasonOf(state.rejectionOf(mint(1, 5))), containsString("not authorized"));

		state.askAuthorityAt(5000);
		assertThat(state.rejectionOf(mint(1, 5)).isPresent(), is(false));
	}

	@Example
	public void shouldAskAtTheTimeOfTheBlockWhenAValidatorChecksIt() {
		final LedgerState state = soloState(mintsWhen((mint, time) -> time >= 5000));
		final LedgerState maker = soloState(ANY_MINT);
		final Block early = block(maker, seeds(1, 1), 1, 4000, List.of(mint(1, 5)));
		final Block late = block(maker, seeds(1, 1), 1, 6000, List.of(mint(1, 5)));

		assertThat(reasonOf(state.proposalRejection(early)), containsString("not authorized"));
		assertThat(state.proposalRejection(late).isPresent(), is(false));
	}

	@Example
	public void shouldAskAtTheTimeItWasToldToWhenPickingAndSealingTheTransactionsOfABlock() {
		final LedgerState state = soloState(mintsWhen((mint, time) -> time >= 5000));
		final LedgerState maker = soloState(ANY_MINT);

		maker.apply(soloBlock(maker, mint(1, 5)));
		state.askAuthorityAt(4999);

		assertThat(state.applicable(state.tipTime(), List.of(mint(1, 5))), equalTo(List.of()));
		assertThrows(IllegalArgumentException.class, () -> state.rootAfter(1, state.tipTime(), List.of(mint(1, 5))));

		state.askAuthorityAt(5000);

		assertThat(state.applicable(state.tipTime(), List.of(mint(1, 5))), equalTo(List.of(mint(1, 5))));
		assertThat(state.rootAfter(1, state.tipTime(), List.of(mint(1, 5))), equalTo(maker.stateRoot()));
	}

	@Example
	public void shouldCarryTheTimeToACopyAndKeepItApartFromTheOriginal() {
		final LedgerState state = soloState(mintsWhen((mint, time) -> time >= 5000));

		state.askAuthorityAt(4999);

		final LedgerState early = state.copy();

		state.askAuthorityAt(5000);

		final LedgerState late = state.copy();

		assertThat(reasonOf(early.rejectionOf(mint(1, 5))), containsString("not authorized"));
		assertThat(late.rejectionOf(mint(1, 5)).isPresent(), is(false));
	}

	@Example
	public void shouldTakeACertifiedBlockWhateverTheTimeSays() {
		final LedgerState maker = soloState(ANY_MINT);
		final LedgerState follower = soloState(mintsWhen((mint, time) -> time >= 5000));

		follower.apply(block(maker, seeds(1, 1), 1, 4000, List.of(mint(1, 5))));

		assertThat(follower.balanceOf(account(1)), equalTo(5L));
	}

	private static LedgerAuthority cappedAt(long cap) {
		return TestAuthority.open().withMintsOnSupply((mint, time, supply) -> supply + mint.amount() <= cap);
	}

	@Example
	public void shouldAskWithTheSupplyThatTheEarlierMintsOfTheBlockHaveMade() {
		final List<String> asked = new ArrayList<>();
		final LedgerState state = soloState(TestAuthority.open()
			.withMintsOnSupply((mint, time, supply) -> asked.add(mint.amount() + " on " + supply)));

		assertThat(state.proposalRejection(soloBlock(soloState(ANY_MINT), mint(1, 5), mint(2, 6))).isPresent(),
			is(false));

		assertThat(asked, equalTo(List.of("5 on 0", "6 on 5")));
	}

	@Example
	public void shouldRefuseTheMintThatPassesTheCapTogetherWithTheOnesBeforeItInTheBlockAndChangeNothing() {
		final LedgerState state = soloState(cappedAt(10));
		final LedgerState maker = soloState(ANY_MINT);
		final byte[] root = state.stateRoot();

		assertThat(state.proposalRejection(soloBlock(maker, mint(1, 5), mint(2, 5))).isPresent(), is(false));
		assertThat(reasonOf(state.proposalRejection(soloBlock(maker, mint(1, 5), mint(2, 6)))),
			containsString("not authorized"));

		assertThat(reasonOf(state.proposalRejection(soloBlock(maker, mint(1, 11)))), containsString("not authorized"));
		assertThat(state.supply(), equalTo(0L));
		assertThat(state.balanceOf(account(1)), equalTo(0L));
		assertThat(state.stateRoot(), equalTo(root));
	}

	@Example
	public void shouldCountTheMintsOfEarlierBlocksInTheSupplyOfTheNextMint() {
		final LedgerState state = soloState(cappedAt(10));
		final LedgerState maker = soloState(ANY_MINT);
		final Block first = soloBlock(maker, mint(1, 5));

		state.apply(first);
		maker.apply(first);

		assertThat(reasonOf(state.rejectionOf(mint(2, 6))), containsString("not authorized"));
		assertThat(state.rejectionOf(mint(2, 5)).isPresent(), is(false));
		assertThat(reasonOf(state.proposalRejection(soloBlock(maker, mint(2, 6)))), containsString("not authorized"));
		assertThat(state.proposalRejection(soloBlock(maker, mint(2, 5))).isPresent(), is(false));
	}

	@Example
	public void shouldKeepOnlyTheMintsThatFitTheCapOneAfterAnotherWhenPickingTheTransactionsOfABlock() {
		final LedgerState state = soloState(cappedAt(10));

		assertThat(state.applicable(state.tipTime(), List.of(mint(1, 5), mint(2, 6))), equalTo(List.of(mint(1, 5))));
		assertThat(state.applicable(state.tipTime(), List.of(mint(2, 6), mint(1, 5))), equalTo(List.of(mint(2, 6))));
		assertThat(state.applicable(state.tipTime(), List.of(mint(1, 5), mint(2, 5))),
			equalTo(List.of(mint(1, 5), mint(2, 5))));
		assertThrows(IllegalArgumentException.class, () -> state.rootAfter(1, state.tipTime(),
			List.of(mint(1, 5), mint(2, 6))));
	}

	@Example
	public void shouldTakeACertifiedBlockWhateverTheSupplyItLeaves() {
		final LedgerState maker = soloState(ANY_MINT);
		final LedgerState follower = soloState(cappedAt(10));

		follower.apply(soloBlock(maker, mint(1, 5), mint(2, 6)));

		assertThat(follower.supply(), equalTo(11L));
		assertThat(follower.balanceOf(account(2)), equalTo(6L));
	}

	private static LedgerAuthority grantingEach(long grant) {
		return TestAuthority.open().withMintsOnTotals((mint, time, supply, minted) -> minted + mint.amount() <= grant);
	}

	/* A second mint to an account, with a reference of its own that depends on the amount */
	private static Mint another(int n, long amount) {
		return new Mint(account(n), amount, reference(1000 + (int) amount));
	}

	@Example
	public void shouldAskWithWhatMintsToTheRecipientHaveMadeInTheBlockAndBefore() {
		final List<String> asked = new ArrayList<>();
		final LedgerState state = soloState(TestAuthority.open().withMintsOnTotals(
			(mint, time, supply, minted) -> asked.add(mint.amount() + " after " + minted)));
		final Block block = soloBlock(soloState(ANY_MINT), mint(1, 5), another(1, 6), mint(2, 7));

		assertThat(state.proposalRejection(block).isPresent(), is(false));
		assertThat(asked, equalTo(List.of("5 after 0", "6 after 5", "7 after 0")));

		asked.clear();
		state.apply(block);
		assertThat(asked, equalTo(List.of()));
		state.rejectionOf(another(1, 8));
		state.rejectionOf(another(3, 9));

		assertThat(asked, equalTo(List.of("8 after 11", "9 after 0")));
	}

	@Example
	public void shouldRefuseTheMintThatPassesWhatTheRecipientMayHaveTogetherWithTheOnesBeforeItInTheBlock() {
		final LedgerState state = soloState(grantingEach(10));
		final LedgerState maker = soloState(ANY_MINT);

		assertThat(state.proposalRejection(soloBlock(maker, mint(1, 5), another(1, 5))).isPresent(), is(false));
		assertThat(reasonOf(state.proposalRejection(soloBlock(maker, mint(1, 5), another(1, 6)))),
			containsString("not authorized"));
		assertThat(state.proposalRejection(soloBlock(maker, mint(1, 10), mint(2, 10))).isPresent(), is(false));
		assertThat(state.mintedTo(account(1)), equalTo(0L));
	}

	@Example
	public void shouldCountTheMintsOfEarlierBlocksAgainstTheRecipient() {
		final LedgerState state = soloState(grantingEach(10));

		state.apply(soloBlock(soloState(ANY_MINT), mint(1, 6)));

		assertThat(reasonOf(state.rejectionOf(another(1, 5))), containsString("not authorized"));
		assertThat(state.rejectionOf(another(1, 4)).isPresent(), is(false));
		assertThat(state.rejectionOf(mint(2, 10)).isPresent(), is(false));
	}

	@Example
	public void shouldKeepOnlyTheMintsThatFitWhatTheRecipientMayHaveWhenPickingTheTransactionsOfABlock() {
		final LedgerState state = soloState(grantingEach(10));

		assertThat(state.applicable(state.tipTime(), List.of(mint(1, 6), another(1, 5), mint(2, 10))),
			equalTo(List.of(mint(1, 6), mint(2, 10))));
		assertThat(state.applicable(state.tipTime(), List.of(another(1, 5), mint(1, 6))), equalTo(List.of(another(1, 5))));
		assertThrows(IllegalArgumentException.class, () -> state.rootAfter(1, state.tipTime(),
			List.of(mint(1, 6), another(1, 5))));
	}

	@Example
	public void shouldTakeACertifiedBlockWhateverTheRecipientHasBeenGiven() {
		final LedgerState follower = soloState(grantingEach(10));

		follower.apply(soloBlock(soloState(ANY_MINT), mint(1, 8), another(1, 9)));

		assertThat(follower.balanceOf(account(1)), equalTo(17L));
		assertThat(follower.mintedTo(account(1)), equalTo(17L));
	}

	@Example
	public void shouldListTheDueMintsWithoutTheOnesAlreadyMadeAndKeepTheirOrder() {
		final Mint first = mint(1, 5);
		final Mint second = mint(2, 6);
		final Mint third = mint(3, 7);
		final LedgerState state = soloState(dueAt(Map.of(first, 1000L, second, 2000L, third, 9000L)));

		assertThat(state.dueMints(1500), equalTo(List.of(first)));
		assertThat(state.dueMints(2500), equalTo(List.of(first, second)));

		state.apply(soloBlock(state, first));

		assertThat(state.dueMints(2500), equalTo(List.of(second)));
	}

	@Example
	public void shouldDemandTheMinimumFeeWhereATransferIsTakenInButNotOfACertifiedBlock() {
		final LedgerState maker = soloState(ANY_MINT);

		maker.apply(soloBlock(maker, mint(1, 1000)));

		final LedgerState picky = soloState(minimumFee(5));

		picky.apply(soloBlock(picky, mint(1, 1000)));

		final Transfer cheap = transfer(picky, 1, account(2), 10, 4, 0);
		final Transfer fair = transfer(picky, 1, account(2), 10, 5, 0);

		assertThat(reasonOf(picky.rejectionOfQueued(cheap)), containsString("Fee is below"));
		assertThat(reasonOf(picky.rejectionOf(cheap)), containsString("Fee is below"));
		assertThat(picky.rejectionOf(fair).isPresent(), is(false));

		picky.apply(block(maker, seeds(1, 1), 1, 2000, List.of(transfer(maker, 1, account(2), 10, 0, 0))));

		assertThat(picky.balanceOf(account(2)), equalTo(10L));
	}

	/* Accounts stay for good and go into every root, so making them costs an amount and not only the fee; topping up
	   one that holds something costs nothing more */
	@Example
	public void shouldDemandTheLeastAmountOfATransferThatOpensAnAccountButNotOfACertifiedBlock() {
		final LedgerState maker = soloState(ANY_MINT);
		final LedgerState picky = soloState(TestAuthority.open().withLeastNewAccountAmount(100));

		maker.apply(soloBlock(maker, mint(1, 1000)));
		picky.apply(soloBlock(picky, mint(1, 1000)));

		final Transfer dust = transfer(picky, 1, account(2), 99, 1, 0);

		assertThat(reasonOf(picky.rejectionOfQueued(dust)), containsString("new account"));
		assertThat(reasonOf(picky.rejectionOf(dust)), containsString("new account"));
		assertThat(picky.rejectionOf(transfer(picky, 1, account(2), 100, 1, 0)).isPresent(), is(false));

		picky.apply(block(maker, seeds(1, 1), 1, 2000, List.of(transfer(maker, 1, account(3), 1, 1, 0))));

		assertThat(picky.balanceOf(account(3)), equalTo(1L));
		assertThat(picky.rejectionOf(transfer(picky, 1, account(3), 1, 1, 1)).isPresent(), is(false));
		assertThat(picky.rejectionOf(transfer(picky, 1, account(1), 1, 1, 1)).isPresent(), is(false));
	}
}
