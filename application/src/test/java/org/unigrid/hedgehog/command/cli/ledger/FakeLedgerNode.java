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

package org.unigrid.hedgehog.command.cli.ledger;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.server.rest.RestToken;

/* One canned answer for each route a ledger command asks, so a test sees what was sent and whether the
   connection was opened and closed */
final class FakeLedgerNode extends MockUp<RestClient> {
	static final String LOCAL = RestCommandFixture.HOST;
	private static final int PORT = 4321;
	private static final String TOKEN = "rest-token";

	static {
		FakeLedgerNode.class.getModule().addOpens(FakeLedgerNode.class.getPackageName(), MockUp.class.getModule());
	}

	private final Response info;
	private final Response account;
	private final Response submitted;
	private final List<Request> requests = new ArrayList<>();
	private boolean connected;
	private boolean closed;

	FakeLedgerNode(Response info, Response account, Response submitted) {
		this(LOCAL, info, account, submitted);
	}

	FakeLedgerNode(String host, Response info, Response account, Response submitted) {
		this.info = info;
		this.account = account;
		this.submitted = submitted;
		fakeOptions(host);
		readBackItsEntity(info);
		readBackItsEntity(account);
		readBackItsEntity(submitted);
	}

	@Mock public void $init(String host, int port, boolean secure, String token, Duration readTimeout) {
		connected = true;
	}

	@Mock public Response get(String location) {
		return answer(HttpMethod.GET, location, null);
	}

	@Mock public Response postBounded(String location, Entity<?> entity) {
		return answer(HttpMethod.POST, location, entity);
	}

	@Mock public void close() {
		closed = true;
	}

	private Response answer(String method, String location, Entity<?> entity) {
		requests.add(new Request(method, location, Optional.ofNullable(entity), Optional.empty()));

		return switch (location) {
			case "/ledger" -> info;
			case "/ledger/transaction" -> submitted;
			default -> account;
		};
	}

	List<Request> requests() {
		return requests;
	}

	boolean connected() {
		return connected;
	}

	boolean closed() {
		return closed;
	}

	private static void fakeOptions(String host) {
		new MockUp<RestOptions>() {
			@Mock public String getHost() {
				return host;
			}

			@Mock public int getPort() {
				return PORT;
			}
		};

		new MockUp<RestToken>() {
			@Mock public String resolve() {
				return TOKEN;
			}
		};
	}

	private static void readBackItsEntity(Response canned) {
		new MockUp<Response>(canned.getClass()) {
			@Mock public Object readEntity(Invocation invocation, Class<?> type) {
				final Object entity = invocation.<Response>getInvokedInstance().getEntity();

				return type == InputStream.class
					? new ByteArrayInputStream(entity.toString().getBytes(StandardCharsets.UTF_8)) : entity;
			}

			@Mock public Object readEntity(Invocation invocation, GenericType<?> type) {
				return invocation.<Response>getInvokedInstance().getEntity();
			}
		};
	}
}
