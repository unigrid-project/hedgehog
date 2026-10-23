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
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.Vote;

/* A vote that misses the end of its round, on a running network that cannot make a block in the meantime: two of the
   four validators are stopped, so the vote waits in the others until the clock of the fleet moves past the end of its
   round, and from then on no node whose clock is past that end keeps it or takes it in. A proposer whose clock ran
   behind could still put it in a block dated in its round, as the time of a block has no lower bound but the tip;
   the nodes of a fleet share one clock, so here none does. The validators stay stopped, so the class has a fleet of
   its own. */
public class LedgerLateVoteAcceptanceTest extends BaseLedgerAcceptanceTest {
	@Example
	public void shouldDropAVoteThatMissedTheEndOfItsRoundAndRefuseItOnceTheClockIsPastIt() {
		final AccountKey candidate = account();

		awaitSigning();
		fleet().awaitSameChain();
		fleet().stop(2);
		fleet().stop(3);

		final long height = fleet().height(0);
		final Vote late = vote(0, candidate, roundOn(0));

		assertThat(fleet().submit(0, late), equalTo(Optional.empty()));
		assertThat(fleet().readOn(0, Ledger::pending), equalTo(1));

		endTheRound();
		awaitNothingWaiting();
		assertRefusedEverywhere(late, Optional.of("Vote is for another round"));
		staysTrue(() -> everywhere(node -> fleet().height(node) == height
			&& fleet().readOn(node, Ledger::pending) == 0));
		assertThat(everywhere(node -> !fleet().readOn(node, Ledger::validators).contains(candidate)), is(true));
	}
}
