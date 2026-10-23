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

package org.unigrid.hedgehog.service.ledger;

import java.util.Optional;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Ledger;

/* Validator rounds on a running network of four validators and an observer that nothing else keeps busy: a round is a
   span of time from the genesis on, and the votes cast in it take effect when the first block of a later round closes
   it, a block with nothing in it when nothing else waits. The end of a round is brought about by moving the clock of
   the fleet, never by waiting for it. */
public class LedgerRoundAcceptanceTest extends BaseLedgerAcceptanceTest {
	private boolean everywhereInTheSet(final AccountKey key) {
		return everywhere(node -> fleet().readOn(node, Ledger::validators).contains(key));
	}

	@Example
	public void shouldLetTheVotesOfAnIdleNetworkTakeEffectOnceTheTimeOfTheirRoundIsOver() {
		final AccountKey fifth = account();

		awaitSigning();
		fleet().awaitSameChain();

		final long round = roundOn(0);
		final long before = fleet().height(0);

		IntStream.range(0, 3).forEach(validator -> assertThat(fleet().submit(validator,
			vote(validator, fifth, round)), equalTo(Optional.empty())));
		awaitNothingWaiting();
		staysAsItIs();

		final long voted = fleet().height(0);

		assertThat(voted, greaterThan(before));
		assertThat(everywhere(node -> !fleet().readOn(node, Ledger::validators).contains(fifth)), is(true));

		endTheRound();
		await().atMost(PATIENCE).until(() -> everywhereInTheSet(fifth));
		staysAsItIs();

		assertThat(fleet().height(0), equalTo(voted + 1));
		assertThat(fleet().readOn(0, ledger -> ledger.block(voted + 1)).orElseThrow().getTransactions(), is(empty()));
		assertThat(roundOn(0), equalTo(round + 1));
	}
}
