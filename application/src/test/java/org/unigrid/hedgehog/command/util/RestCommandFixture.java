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

package org.unigrid.hedgehog.command.util;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.server.rest.RestToken;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RestCommandFixture {
	public static final String HOST = "node.test";
	public static final int PORT = 4321;
	public static final String TOKEN = "fixture-token";

	static {
		/* Logback reports its own start-up on stdout, which must not end up in the output of a command */
		LoggerFactory.getILoggerFactory();

		/* JMockit calls the fakes reflectively, but a test run only opens the packages of the tests it selects */
		RestCommandFixture.class.getModule().addOpens(RestCommandFixture.class.getPackageName(),
			MockUp.class.getModule());
	}

	public record Connection(String host, int port, boolean secure, String token) { }

	public record Request(String method, String location, Optional<Entity<?>> entity,
		Optional<MultivaluedMap<String, Object>> headers) { }

	public record Result(Optional<Connection> connection, boolean closed, Optional<Request> request, String out,
		String err) { }

	private interface Answer {
		Response respond() throws ResponseOddityException;
	}

	private interface Token {
		String resolve() throws IOException;
	}

	public static Result run(Runnable command, Response canned) {
		readsBackItsEntity(canned);
		return run(command, () -> TOKEN, () -> canned);
	}

	public static Result runWithOddity(Runnable command, ResponseOddityException oddity) {
		return run(command, () -> TOKEN, () -> {
			throw oddity;
		});
	}

	public static Result runWithoutToken(Runnable command, IOException unreadable) {
		return run(command, () -> {
			throw unreadable;
		}, () -> {
			throw new IllegalStateException("A request went out without a token");
		});
	}

	private static Result run(Runnable command, Token token, Answer answer) {
		final FakeClient client = new FakeClient(answer);
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final ByteArrayOutputStream err = new ByteArrayOutputStream();
		final PrintStream originalOut = System.out;
		final PrintStream originalErr = System.err;

		fakeOptions(token);
		System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
		System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));

		try {
			command.run();
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}

		return new Result(client.connection, client.closed, client.request, out.toString(StandardCharsets.UTF_8),
			err.toString(StandardCharsets.UTF_8));
	}

	private static void fakeOptions(Token token) {
		new MockUp<RestOptions>() {
			@Mock public String getHost() {
				return HOST;
			}

			@Mock public int getPort() {
				return PORT;
			}
		};

		new MockUp<RestToken>() {
			@Mock public String resolve() throws IOException {
				return token.resolve();
			}
		};
	}

	/* An outbound response refuses to be read, but a command reads what the daemon sent: the canned entity */
	private static void readsBackItsEntity(Response canned) {
		new MockUp<Response>(canned.getClass()) {
			@Mock public Object readEntity(Invocation invocation, Class<?> type) {
				return invocation.<Response>getInvokedInstance().getEntity();
			}

			@Mock public Object readEntity(Invocation invocation, GenericType<?> type) {
				return invocation.<Response>getInvokedInstance().getEntity();
			}
		};
	}

	private static final class FakeClient extends MockUp<RestClient> {
		private final Answer answer;
		private Optional<Connection> connection = Optional.empty();
		private Optional<Request> request = Optional.empty();
		private boolean closed;

		private FakeClient(Answer answer) {
			this.answer = answer;
		}

		/* Every shorter constructor delegates here, and a faked constructor still makes its delegating call */
		@Mock public void $init(String host, int port, boolean secure, String token, Duration readTimeout) {
			connection = Optional.of(new Connection(host, port, secure, token));
		}

		@Mock public Response get(String location) throws ResponseOddityException {
			return respond(HttpMethod.GET, location, null, null);
		}

		@Mock public Response getWithHeaders(String location, MultivaluedMap<String, Object> headers)
			throws ResponseOddityException {

			return respond(HttpMethod.GET, location, null, headers);
		}

		@Mock public Response post(String location, Entity<?> entity) throws ResponseOddityException {
			return respond(HttpMethod.POST, location, entity, null);
		}

		/* The command closes a streamed body once sent, so its bytes are kept instead, and its length is the
		   Content-Length that the connection announces */
		@Mock public Response postStream(String location, InputStream body, long length)
			throws ResponseOddityException, IOException {

			return respond(HttpMethod.POST, location, Entity.entity(body.readAllBytes(),
				MediaType.APPLICATION_OCTET_STREAM), new MultivaluedHashMap<>(Map.of(HttpHeaders.CONTENT_LENGTH, length)));
		}

		@Mock public Response put(String location, Entity<?> entity) throws ResponseOddityException {
			return respond(HttpMethod.PUT, location, entity, null);
		}

		@Mock public Response putWithHeaders(String location, Entity<?> entity,
			MultivaluedMap<String, Object> headers) throws ResponseOddityException {

			return respond(HttpMethod.PUT, location, entity, headers);
		}

		@Mock public Response delete(String location) throws ResponseOddityException {
			return respond(HttpMethod.DELETE, location, null, null);
		}

		@Mock public Response deleteWithHeaders(String location, MultivaluedMap<String, Object> headers)
			throws ResponseOddityException {

			return respond(HttpMethod.DELETE, location, null, headers);
		}

		@Mock public void close() {
			closed = true;
		}

		private Response respond(String method, String location, Entity<?> entity,
			MultivaluedMap<String, Object> headers) throws ResponseOddityException {

			request = Optional.of(new Request(method, location, Optional.ofNullable(entity),
				Optional.ofNullable(headers)));

			return answer.respond();
		}
	}
}
