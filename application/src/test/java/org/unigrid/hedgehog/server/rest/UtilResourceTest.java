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

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.UniqueElements;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.jqwik.TestFileOutput;
import org.unigrid.hedgehog.model.crypto.Signature;

public class UtilResourceTest extends BaseRestClientTest {

	@Provide
	public Arbitrary<String> provideVersion(@ForAll @Positive int major, @ForAll @Positive int minor,
		@ForAll @Positive int patch) {
		StringBuilder sb = new StringBuilder();
		sb.append(major);
		sb.append(".");
		sb.append(minor);
		sb.append(".");
		sb.append(patch);
		return Arbitraries.of(sb.toString());
	}

	@SneakyThrows
	@Property(tries = 5)
	public void shouldGetRequierdVersion() {
		final String url = "/minimum/version";

		Response response = client.get(url);
		String json = response.readEntity(String.class);
		System.out.println(json);
		assertThat(Response.Status.fromStatusCode(response.getStatus()), equalTo(Response.Status.OK));
	}

	@SneakyThrows
	@Property(tries = 1)
	public void shouldVerifyUpdateOfRequierdVersion(@ForAll("provideSignature") Signature signature,
		@ForAll @UniqueElements @Size(3) List<@From("provideVersion") String> versions) {

		Response.Status expectedStatusFromPut;
		Response response;
		final String url = "/version/%s/%s".formatted(versions.get(1), versions.get(2));

		if (Response.Status.fromStatusCode(client.get("/minimum/version")
			.getStatus()) != Response.Status.OK) {
			expectedStatusFromPut = Response.Status.OK;
		} else {
			expectedStatusFromPut = Response.Status.NO_CONTENT;
		}

		response = client.putWithHeaders(url, Entity.text(versions.get(0)),
			new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
		);

		assertThat(Response.Status.fromStatusCode(response.getStatus()),
			equalTo(expectedStatusFromPut)
		);
	}
}
