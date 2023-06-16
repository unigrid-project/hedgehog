/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.util.Map;
import lombok.SneakyThrows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.MintSupply;

public class MintSupplyResourceTest extends BaseRestClientTest {
	@SneakyThrows
	@Property(tries = 50)
	public void shoulBeChangeable(@ForAll("provideSignature") Signature signature,
		@ForAll @BigRange(min = "0", max = "1000000") BigDecimal maxSupply) {

		final String url = "/gridspork/mint-supply/";
		final Response response = client.get(url);
		BigDecimal originalMaxSupply = BigDecimal.ZERO;

		if (Status.fromStatusCode(response.getStatus()) == Status.OK) {
			final MintSupply.SporkData data = response.readEntity(MintSupply.class).getData();
			originalMaxSupply = data.getMaxSupply();
		}

		final Response putResponse = client.putWithHeaders(url, Entity.text(maxSupply),
			new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
		);

		final MintSupply.SporkData data = client.getEntity(url, MintSupply.class).getData();

		if (Status.fromStatusCode(putResponse.getStatus()) == Status.OK) {
			assertThat(data.getMaxSupply(), equalTo(maxSupply));
		} else {
			assertThat(data.getMaxSupply(), equalTo(originalMaxSupply));
		}
	}
}
