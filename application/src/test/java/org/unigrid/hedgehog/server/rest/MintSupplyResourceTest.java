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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;

public class MintSupplyResourceTest extends BaseRestClientTest {
	private static final String URL = "/gridspork/mint-supply/";

	@Inject private PendingSporks pendingSporks;

	@BeforeTry
	public void leaveNoProposalOfTheTriesBefore() {
		pendingSporks.remove(Type.MINT_SUPPLY);
	}

	@SneakyThrows
	private BigDecimal storedMaxSupply() {
		final Response response = client.get(URL);

		if (Status.fromStatusCode(response.getStatus()) == Status.OK) {
			return response.readEntity(MintSupply.class).<MintSupply.SporkData>getData().getMaxSupply();
		}

		return BigDecimal.ZERO;
	}

	@SneakyThrows
	private Response propose(Signature signature, BigDecimal maxSupply) {
		return client.putWithHeaders(URL, Entity.text(maxSupply),
			new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
		);
	}

	@SneakyThrows
	@Property(tries = 50)
	public void shoulBeChangeableOnceCosigned(@ForAll("provideSignature") Signature signature,
		@ForAll @BigRange(min = "0", max = "1000000") BigDecimal maxSupply) {

		final BigDecimal originalMaxSupply = storedMaxSupply();
		final Response putResponse = propose(signature, maxSupply);

		assertThat(Status.fromStatusCode(putResponse.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(storedMaxSupply(), equalTo(originalMaxSupply));
		assertThat(Status.fromStatusCode(cosign(putResponse).getStatus()), equalTo(Status.OK));
		assertThat(storedMaxSupply(), equalTo(maxSupply));
	}

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void refusesAMaxSupplyThatNoNodeCanHold(@ForAll("provideSignature") Signature signature) {
		final BigDecimal originalMaxSupply = storedMaxSupply();

		for (final String maxSupply : List.of("1E+999999999", "-1", "0.123456789")) {
			assertThat(maxSupply, assertThrows(ResponseOddityException.class,
				() -> propose(signature, new BigDecimal(maxSupply))).getMessage(), startsWith("400 "));
			assertThat(maxSupply, pendingSporks.proposalOf(Type.MINT_SUPPLY).isPresent(), is(false));
			assertThat(maxSupply, storedMaxSupply(), equalTo(originalMaxSupply));
		}
	}

	@SneakyThrows
	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void takesTheLargestMaxSupplyThatCanBeMinted(@ForAll("provideSignature") Signature signature) {
		assertThat(Status.fromStatusCode(propose(signature, BigDecimal.valueOf(1_000_000)).getStatus()),
			equalTo(Status.ACCEPTED));
	}
}
