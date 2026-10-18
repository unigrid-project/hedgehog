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

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jakarta.ws.rs.core.Response.Status;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

/* The container queues whatever a client sends before a resource reads it, so what a request may hold is bounded
   where it arrives, on a real server, and not by the resource that reads it */
public class RestBodyLimitTest extends BaseRestClientTest {
	private static final long LIMIT = RequestBodyLimit.MAX_REQUEST_BYTES;
	private static final int CHUNK = 64 * 1024;
	private static final int CHUNKS = 64;
	private static final Duration PATIENCE = Duration.ofSeconds(10);
	private static final byte[] CRLF = { '\r', '\n' };

	private record Answer(String status, String body, boolean closed) { }

	@SneakyThrows
	private SSLSocket connect() {
		final SSLContext context = SSLContext.getInstance("TLS");
		final InetSocketAddress address = (InetSocketAddress) server.getRest().getChannel().localAddress();

		context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);

		final SSLSocket socket = (SSLSocket) context.getSocketFactory()
			.createSocket(address.getAddress(), address.getPort());

		socket.setSoTimeout(Math.toIntExact(PATIENCE.toMillis()));
		return socket;
	}

	private String head(final String path, final String framing) {
		return "POST " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + server.getRest().getToken()
			+ "\r\nContent-Type: application/json\r\n" + framing + "\r\n\r\n";
	}

	/* A server that hangs up on a sender that goes on is what is wanted, so a write that fails ends the sending */
	private static void sendChunked(final OutputStream out, final int chunks) {
		final byte[] size = (Integer.toHexString(CHUNK) + "\r\n").getBytes(StandardCharsets.US_ASCII);
		final byte[] data = new byte[CHUNK];

		Arrays.fill(data, (byte) ' ');

		try {
			for (int i = 0; i < chunks; i++) {
				out.write(size);
				out.write(data);
				out.write(CRLF);
			}

			out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
			out.flush();
		} catch (IOException ex) {
			return;
		}
	}

	private static String readLine(final InputStream in) throws IOException {
		final ByteArrayOutputStream line = new ByteArrayOutputStream();

		for (int read = in.read(); read != -1 && read != '\n'; read = in.read()) {
			line.write(read);
		}

		return line.toString(StandardCharsets.US_ASCII).trim();
	}

	/* Headers up to the blank line, then as many body bytes as they announce, then whether the connection ends */
	private static Answer answerOf(final InputStream in) throws IOException {
		final String status = readLine(in);
		int length = 0;

		for (String header = readLine(in); !header.isEmpty(); header = readLine(in)) {
			if (header.toLowerCase().startsWith("content-length:")) {
				length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
			}
		}

		final String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);

		return new Answer(status, body, isClosed(in));
	}

	private static boolean isClosed(final InputStream in) {
		try {
			return in.read() == -1;
		} catch (SocketTimeoutException ex) {
			return false;
		} catch (IOException ex) {
			return true;
		}
	}

	@SneakyThrows
	private Answer answerTo(final String framing, final Consumer<OutputStream> body) {
		try (SSLSocket socket = connect()) {
			final OutputStream out = socket.getOutputStream();

			out.write(head("/ledger/transaction", framing).getBytes(StandardCharsets.US_ASCII));
			out.flush();
			body.accept(out);
			return answerOf(socket.getInputStream());
		} catch (SocketTimeoutException ex) {
			return new Answer("no answer", "", false);
		}
	}

	@Example
	public void shouldRefuseAChunkedBodyPastTheLimitAndCloseTheConnection() {
		final Answer answer = answerTo("Transfer-Encoding: chunked", out -> sendChunked(out, CHUNKS));

		assertThat(answer.status(), startsWith("HTTP/1.1 " + Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode()));
		assertThat(answer.closed(), is(true));
		assertThat(answer.body(), containsString("A request is at most " + LIMIT + " bytes"));
	}

	@Example
	public void shouldRefuseALengthPastTheLimitBeforeAnyOfTheBodyIsSent() {
		final Answer answer = answerTo("Content-Length: " + (LIMIT + 1), out -> { });

		assertThat(answer.status(), startsWith("HTTP/1.1 " + Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode()));
		assertThat(answer.body(), containsString("A request is at most " + LIMIT + " bytes"));
		assertThat(answer.closed(), is(true));
	}

	@Example
	public void shouldLetABodyAtTheLimitReachTheResource() {
		final Answer answer = answerTo("Content-Length: " + LIMIT, out -> sendLength(out, Math.toIntExact(LIMIT)));

		assertThat(answer.status(), startsWith("HTTP/1.1 " + Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode()));
		assertThat(answer.body(), containsString("A request is at most 4096 bytes"));
	}

	@Example
	@SneakyThrows
	public void shouldGoOnAnsweringOthersAfterRefusingOne() {
		answerTo("Transfer-Encoding: chunked", out -> sendChunked(out, CHUNKS));

		assertThat(client.get("/version").getStatus(), equalTo(Status.OK.getStatusCode()));
	}

	@SneakyThrows
	private static void sendLength(final OutputStream out, final int length) {
		final byte[] data = new byte[length];

		Arrays.fill(data, (byte) ' ');
		out.write(data);
		out.flush();
	}
}
