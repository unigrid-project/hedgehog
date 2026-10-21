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

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.mint;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.seed;

/* The transactions that wait for a block are in the ledger the driver holds, and a node that is replaced has a ledger
   of its own: a transaction that was taken is still waiting for its block after the replacement */
public class LedgerDriverPoolTest {
	@Example
	public void shouldKeepWhatWaitsForABlockWhenTheObserverIsPromoted() throws IOException {
		try (DriverRig rig = new DriverRig(DriverRig.FOUR, seed(2), DriverRig.STRICT, new DirectSerial()).started()) {
			assertThat(rig.driver.submit(mint(1, 500)).join(), equalTo(Optional.empty()));
			assertThat(rig.driver.signing(), is(false));
			assertThat(rig.ledger().pending(), is(1));

			rig.passGrace();

			assertThat(rig.driver.signing(), is(true));
			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(rig.ledger().pending(), is(1));
		}
	}

	@Example
	public void shouldKeepWhatWaitsForABlockWhenANodeThatFailedIsReplaced() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			assertThat(rig.driver.submit(mint(1, 500)).join(), equalTo(Optional.empty()));

			rig.breakNode();
			rig.tick(DriverRig.TICK);

			assertThat(rig.built, equalTo(List.of(true, true)));
			assertThat(rig.ledger().pending(), is(1));
		}
	}

	@Example
	public void shouldKeepWhatWaitsForABlockWhenAVoterCannotBeBuiltAndTheObserverIsBuiltAgain() throws IOException {
		try (DriverRig rig = new DriverRig(DriverRig.FOUR, seed(2), DriverRig.STRICT, new DirectSerial()).started()) {
			assertThat(rig.driver.submit(mint(1, 500)).join(), equalTo(Optional.empty()));

			rig.failCreationOfVoters(new IOException("The key is in use"));
			rig.passGrace();

			assertThat(rig.driver.signing(), is(false));
			assertThat(rig.built, equalTo(List.of(false, true, false)));
			assertThat(rig.ledger().pending(), is(1));
		}
	}
}
