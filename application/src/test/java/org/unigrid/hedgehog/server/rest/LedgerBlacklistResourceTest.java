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

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Optional;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerBlacklistTest;

/* A list that the spork refuses cannot be signed, as its wire form cannot hold it: the command line refuses it first
   (LedgerBlacklistCommandTest), and a node refuses one past the cap of the wire (GridSporkWireResourceTest) */
public class LedgerBlacklistResourceTest extends BaseRestClientTest {
	private static final String URL = "/gridspork/ledger-blacklist";

	@Provide
	public Arbitrary<Set<String>> validLists() {
		return LedgerBlacklistTest.cappedSets();
	}

	private Optional<Set<String>> stored() throws ResponseOddityException {
		final Response response = client.get(URL);

		if (Status.fromStatusCode(response.getStatus()) == Status.NO_CONTENT) {
			return Optional.empty();
		}

		return Optional.of(response.readEntity(LedgerBlacklist.class).<LedgerBlacklist.SporkData>getData()
			.getLegacyAddresses());
	}

	private GridSpork listing(Set<String> addresses, Signature signer) {
		return next(Type.LEDGER_BLACKLIST, (LedgerBlacklist.SporkData data) -> data.setLegacyAddresses(addresses),
			signer);
	}

	@SneakyThrows
	private void assertStores(final Set<String> addresses) {
		final Response proposal = submit(listing(addresses, PROPOSER));

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));
		assertThat(stored(), equalTo(Optional.of(addresses)));
	}

	@Property(tries = 5)
	public void storesAnySetOfHashes(@ForAll("provideSignature") final Signature signature,
		@ForAll("validLists") final Set<String> addresses) {

		assertStores(addresses);
	}

	@Property(tries = 1)
	public void storesTheLargestList(@ForAll("provideSignature") final Signature signature) {
		assertStores(LedgerBlacklistTest.distinctHashes(LedgerBlacklist.SporkData.MAX_ENTRIES));
	}

	@SneakyThrows
	@Property(tries = 2)
	public void rejectsAListThatNoBoardKeySigned(@ForAll("provideSignature") final Signature trusted,
		@ForAll("validLists") final Set<String> addresses) {

		final Optional<Set<String>> before = stored();

		assertThat(Status.fromStatusCode(submit(listing(addresses, STRANGER)).getStatus()),
			equalTo(Status.UNAUTHORIZED));
		assertThat(stored(), equalTo(before));
	}
}
