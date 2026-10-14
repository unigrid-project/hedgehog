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
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerConservationTest {
	private static final int ACCOUNTS = 4;

	/* Every balance that can exist in this test: the accounts, and the one validator that is paid the fees */
	private static long held(LedgerState state) {
		long total = state.pool() + state.unclaimed() + state.balanceOf(key(1));

		for (int n = 1; n <= ACCOUNTS; n++) {
			total += state.balanceOf(account(n));
		}

		return total;
	}

	private static Transaction random(LedgerState state, Random random, int blockNumber, int index) {
		final int from = 1 + random.nextInt(ACCOUNTS);

		if (random.nextInt(3) == 0) {
			return new Mint(account(from), 1 + random.nextInt(1000), reference(blockNumber * 10 + index));
		}

		return transfer(state, from, account(1 + random.nextInt(ACCOUNTS)), 1 + random.nextInt(300),
			random.nextInt(20), state.nonceOf(account(from)) + random.nextInt(2));
	}

	@Property(tries = 40)
	public void shouldNeverCreateOrLoseMoneyWhateverIsSent(@ForAll long seed) {
		final Random random = new Random(seed);
		final LedgerState state = soloState(ANY_MINT);

		for (int blockNumber = 0; blockNumber < 8; blockNumber++) {
			final List<Transaction> candidates = new ArrayList<>();

			for (int index = 0; index < 6; index++) {
				candidates.add(random(state, random, blockNumber, index));
			}

			final List<Transaction> kept = state.applicable(candidates);

			if (!kept.isEmpty() || state.pool() > 0) {
				state.apply(soloBlock(state, kept.toArray(new Transaction[0])));
			}

			assertThat(held(state), equalTo(state.supply()));
		}
	}
}
