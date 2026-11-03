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

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response.Status;
import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.Expect.Action;
import org.unigrid.hedgehog.model.storage.Fingerprint;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;
import org.unigrid.hedgehog.service.ledger.LedgerService;
import org.unigrid.hedgehog.service.storage.FragmentKeeper;
import org.unigrid.hedgehog.service.storage.StorageService;

/* A node run without --preview keeps the endpoints of the parts that this release leaves off, so that a client is told
   why none of them serves it rather than that there is no such endpoint, and reaches nothing that those parts stand
   on. Every other endpoint answers as before. */
public class PreviewPartResourceTest extends BaseRestClientTest {
	private static final String ADDRESS = "0".repeat(64);
	private static final byte[] FILE = new byte[] { 1, 2, 3, 4 };

	private static final MultivaluedMap<String, Object> FINGERPRINT = new MultivaluedHashMap<>(Map.of(
		StorageResource.FINGERPRINT_HEADER, Fingerprint.generate(new SecureRandom()).encode()));

	@Inject private BeanManager beanManager;

	private static void assertLeftOff(final String part, final Action request) {
		final ResponseOddityException refusal = assertThrows(ResponseOddityException.class, request);

		assertThat(refusal.getMessage(), startsWith("503 "));
		assertThat(refusal.getBody(), equalTo("This release does not include the " + part + " part"));
	}

	/* Asks the context for the instance it holds, which creates none */
	private boolean isCreated(final Class<?> type) {
		final Bean<?> bean = beanManager.resolve(beanManager.getBeans(type));

		return Objects.nonNull(beanManager.getContext(bean.getScope()).get(bean));
	}

	@Example
	public void shouldAnswerEveryLedgerEndpointAsLeftOff() {
		assertLeftOff("ledger", () -> client.get("/ledger"));
		assertLeftOff("ledger", () -> client.get("/ledger/account/" + ADDRESS));
		assertLeftOff("ledger", () -> client.get("/ledger/block/1"));
		assertLeftOff("ledger", () -> client.postBounded("/ledger/transaction",
			Entity.json("{\"transaction\":\"00\"}")));

		assertThat(isCreated(LedgerService.class), is(false));
	}

	@Example
	public void shouldAnswerEveryStorageEndpointAsLeftOff() {
		assertLeftOff("storage", () -> client.postStream("/storage", new ByteArrayInputStream(FILE), FILE.length));
		assertLeftOff("storage", () -> client.getWithHeaders("/storage", FINGERPRINT));
		assertLeftOff("storage", () -> client.deleteWithHeaders("/storage", FINGERPRINT));

		assertThat(List.of(StorageService.class, FragmentKeeper.class, FragmentStore.class).stream()
			.filter(this::isCreated).toList(), is(empty()));
	}

	@Example
	public void shouldAnswerEveryGridnodeEndpointAsLeftOff() {
		assertLeftOff("gridnode", () -> client.get("/gridnode"));
		assertLeftOff("gridnode", () -> client.get("/gridnode/collateral"));
		assertLeftOff("gridnode", () -> client.put("/gridnode/start", Entity.text("")));
		assertLeftOff("gridnode", () -> client.put("/gridnode/stop", Entity.text("")));
	}

	@SneakyThrows
	@Example
	public void shouldAnswerTheEndpointsOfThisReleaseAsBefore() {
		for (final String location : List.of("/gridspork/mint-supply", "/gridspork/mint-storage",
			"/gridspork/board-keys", "/version")) {

			assertThat(location, client.get(location).getStatus(), is(oneOf(Status.OK.getStatusCode(),
				Status.NO_CONTENT.getStatusCode())));
		}
	}
}
