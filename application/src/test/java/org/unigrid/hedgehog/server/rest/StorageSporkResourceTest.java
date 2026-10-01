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

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.spork.StorageSporkTest;

public class StorageSporkResourceTest extends BaseRestClientTest {
	private static final String URL = "/gridspork/storage";

	@Provide
	public Arbitrary<StorageSpork.SporkData> validData() {
		return StorageSporkTest.sporkDataAcrossTheBounds().filter(StorageSporkTest::accepts);
	}

	@Provide
	public Arbitrary<StorageSpork.SporkData> invalidData() {
		return StorageSporkTest.sporkDataAcrossTheBounds().filter(data -> !StorageSporkTest.accepts(data));
	}

	private Response put(final StorageSpork.SporkData data, final String privateKey) throws ResponseOddityException {
		return client.putWithHeaders(URL, Entity.json(data),
			new MultivaluedHashMap<>(Map.of("privateKey", privateKey)));
	}

	private Optional<StorageSpork.SporkData> stored() throws ResponseOddityException {
		final Response response = client.get(URL);

		if (Status.fromStatusCode(response.getStatus()) == Status.NO_CONTENT) {
			return Optional.empty();
		}

		return Optional.of(response.readEntity(StorageSpork.class).getData());
	}

	@SneakyThrows
	@Property(tries = 10)
	public void storesValidParameters(@ForAll("provideSignature") final Signature signature,
		@ForAll("validData") final StorageSpork.SporkData data) {

		final Response proposal = put(data, signature.getPrivateKey());

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));
		assertThat(stored(), equalTo(Optional.of(data)));
	}

	@SneakyThrows
	@Property(tries = 5)
	public void rejectsInvalidParameters(@ForAll("provideSignature") final Signature signature,
		@ForAll("invalidData") final StorageSpork.SporkData data) {

		final Optional<StorageSpork.SporkData> before = stored();

		/* The client refuses any status it does not expect, a 400 among them */
		final ResponseOddityException refusal = assertThrows(ResponseOddityException.class,
			() -> put(data, signature.getPrivateKey()));

		assertThat(refusal.getMessage(), startsWith("400 "));
		assertThat(stored(), equalTo(before));
	}

	/* The trusted signature is only drawn to install a network key that the fresh one then fails to match */
	@SneakyThrows
	@Property(tries = 3)
	public void rejectsUntrustedKeys(@ForAll("provideSignature") final Signature trusted,
		@ForAll("validData") final StorageSpork.SporkData data) {

		final Optional<StorageSpork.SporkData> before = stored();

		assertThat(Status.fromStatusCode(put(data, new Signature().getPrivateKey()).getStatus()),
			equalTo(Status.UNAUTHORIZED));
		assertThat(stored(), equalTo(before));
	}
}
