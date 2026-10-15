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

package org.unigrid.hedgehog.model;

import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.is;

public class NetworkTest {
	private static final String[] EARLIER_RELEASE = { "hedgehog/0.0.4", "gridspork/0.0.4" };

	/* The ledger protocol comes first, and a node of an earlier release is left to settle on the one it offers first */
	@Example
	public void offersTheLedgerProtocolFirstAndTheProtocolsOfTheEarlierReleaseAfterIt() {
		assertThat(Network.getProtocols(), arrayContaining("hedgehog-ledger/0.0.1", EARLIER_RELEASE[0],
			EARLIER_RELEASE[1]));
		assertThat(Network.LEDGER_PROTOCOL, is(Network.getProtocols()[0]));
	}

	@Example
	public void tellsWhetherTheLedgerProtocolIsOffered() {
		assertThat(Network.hasLedger(Network.getProtocols()), is(true));
		assertThat(Network.hasLedger(EARLIER_RELEASE), is(false));
		assertThat(Network.hasLedger(new String[0]), is(false));
	}
}
