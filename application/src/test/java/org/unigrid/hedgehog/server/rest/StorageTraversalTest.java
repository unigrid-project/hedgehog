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
import static org.hamcrest.Matchers.startsWith;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.service.StorageNames;

/* Every name here is refused before the storage services build a single file path */
public class StorageTraversalTest extends BaseRestClientTest {
	private static final String BUCKET = "bucket";

	@SneakyThrows
	private void assertBadRequest(Callable<Response> request) {
		try {
			request.call();
			assertThat("Unexpected response", false);
		} catch (ResponseOddityException ex) {
			assertThat(ex.getMessage(), startsWith(String.valueOf(Status.BAD_REQUEST.getStatusCode())));
		}
	}

	@Provide
	public Arbitrary<String> provideEscaping() {
		return StorageNames.escaping().filter(name -> !name.isEmpty() && name.indexOf('\u0000') == -1);
	}

	@Property(tries = 20)
	public void shouldRefuseEscapingCopySource(@ForAll("provideEscaping") String key) {
		assertBadRequest(() -> client.putWithHeaders("/storage-object/" + BUCKET + "/copy", Entity.text(""),
			new MultivaluedHashMap<>(Map.of("x-amz-copy-source", BUCKET + "/" + key))
		));
	}

	@Example
	public void shouldRefuseEncodedParentKey() {
		assertBadRequest(() -> client.get("/storage-object/" + BUCKET + "/%2E%2E"));
		assertBadRequest(() -> client.delete("/storage-object/" + BUCKET + "/%2E%2E"));
		assertBadRequest(() -> client.delete("/bucket/%2E%2E"));
	}
}
