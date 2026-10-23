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

package org.unigrid.hedgehog.server.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerBlacklistTest;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;

/* A proposal, to this node or to another, waits for its co-signature and changes nothing: the node is never behind
   because of it, so one network key cannot hold back what the ledger does */
public class SporkLagRestTest extends BaseRestClientTest {
	@Inject private SporkLag lag;
	@Inject private SporkDatabase sporkDatabase;

	private boolean isBehind() {
		return lag.isBehind(Type.LEDGER_BLACKLIST, sporkDatabase);
	}

	/* One property for both, as the lag of the node is shared by the tries of a class and one try would leave the other
	   a node that is behind already */
	@Property(tries = 1)
	public void shouldNotFallBehindForAProposalOrARenewalMadeToThisNode(
		@ForAll("provideSignature") final Signature signature) {

		assertThat(isBehind(), is(false));

		final Response proposal = propose(Type.LEDGER_BLACKLIST, (LedgerBlacklist.SporkData data) ->
			data.setLegacyAddresses(LedgerBlacklistTest.distinctHashes(2)));

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(isBehind(), is(false));

		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));
		assertThat(isBehind(), is(false));

		assertThat(Status.fromStatusCode(renew(Type.LEDGER_BLACKLIST, signature).getStatus()),
			equalTo(Status.ACCEPTED));
		assertThat(isBehind(), is(false));
	}
}
