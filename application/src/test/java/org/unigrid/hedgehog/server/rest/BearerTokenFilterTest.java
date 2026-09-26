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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.client.RestClient;

public class BearerTokenFilterTest extends BaseRestClientTest {
	private static final int TOKEN_LENGTH = 43;

	private RestClient newClient(String token) {
		return new RestClient(server.getRest().getHostName(), server.getRest().getPort(), true, token);
	}

	@SneakyThrows
	private void assertRefused(Callable<Response> request) {
		try {
			request.call();
			assertThat("Unexpected response", false);
		} catch (ResponseOddityException ex) {
			assertThat(ex.getMessage(), startsWith(String.valueOf(Status.UNAUTHORIZED.getStatusCode())));
		}
	}

	private void assertRefusedAuthorization(String authorization) {
		try (RestClient anonymous = newClient(null)) {
			assertRefused(() -> anonymous.putWithHeaders("/version", Entity.text(""),
				new MultivaluedHashMap<>(Map.of(HttpHeaders.AUTHORIZATION, authorization))
			));
		}
	}

	@Example
	public void shouldRefuseRequestsWithoutToken() {
		try (RestClient anonymous = newClient(null)) {
			assertRefused(() -> anonymous.get("/version"));
			assertRefused(() -> anonymous.post("/stop", Entity.text("")));
			assertRefused(() -> anonymous.delete("/node/127.0.0.1:1"));
		}
	}

	@Example
	public void shouldRefuseUnknownPathsWithoutToken() {
		try (RestClient anonymous = newClient(null)) {
			assertRefused(() -> anonymous.get("/no-such-endpoint"));
		}
	}

	@Property(tries = 20)
	public void shouldRefuseWrongToken(@ForAll @AlphaChars @StringLength(min = 1, max = 64) String token) {
		try (RestClient wrong = newClient(token)) {
			assertRefused(() -> wrong.get("/version"));
		}
	}

	@Property(tries = 20)
	public void shouldRefuseAlteredToken(@ForAll @IntRange(min = 0, max = TOKEN_LENGTH - 1) int position,
		@ForAll @AlphaChars char replacement) {

		final StringBuilder token = new StringBuilder(server.getRest().getToken());

		Assume.that(token.charAt(position) != replacement);
		token.setCharAt(position, replacement);

		try (RestClient altered = newClient(token.toString())) {
			assertRefused(() -> altered.get("/status"));
		}
	}

	@Property(tries = 20)
	public void shouldRefuseTruncatedToken(@ForAll @IntRange(min = 0, max = TOKEN_LENGTH - 1) int length) {
		try (RestClient truncated = newClient(server.getRest().getToken().substring(0, length))) {
			assertRefused(() -> truncated.get("/status"));
		}
	}

	@Property(tries = 20)
	public void shouldRefuseExtendedToken(@ForAll @AlphaChars @StringLength(min = 1, max = 16) String suffix) {
		try (RestClient extended = newClient(server.getRest().getToken() + suffix)) {
			assertRefused(() -> extended.get("/status"));
		}
	}

	@Property(tries = 10)
	public void shouldRefuseOtherSchemes(@ForAll("provideScheme") String scheme) {
		assertRefusedAuthorization(scheme + server.getRest().getToken());
	}

	@Provide
	public Arbitrary<String> provideScheme() {
		return Arbitraries.of("", "Basic ", "Digest ", "bearer ", "BEARER ", "Bearer  ", "Bearer\t");
	}

	@Example
	@SneakyThrows
	public void shouldAcceptServerToken() {
		assertThat(Status.fromStatusCode(client.get("/status").getStatus()), equalTo(Status.OK));
	}
}
