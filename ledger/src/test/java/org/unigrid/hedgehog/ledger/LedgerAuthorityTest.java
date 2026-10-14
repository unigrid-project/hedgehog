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

		assertThat(state.applicable(List.of(mint(1, 5))), equalTo(List.of()));
		assertThrows(IllegalArgumentException.class, () -> state.rootAfter(1, List.of(mint(1, 5))));

		state.askAuthorityAt(5000);

		assertThat(state.applicable(List.of(mint(1, 5))), equalTo(List.of(mint(1, 5))));
		assertThat(state.rootAfter(1, List.of(mint(1, 5))), equalTo(maker.stateRoot()));
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
}
