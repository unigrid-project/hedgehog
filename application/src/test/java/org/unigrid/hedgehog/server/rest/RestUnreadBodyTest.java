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

import io.netty.buffer.PoolArenaMetric;
import io.netty.buffer.PoolChunkListMetric;
import io.netty.buffer.PoolChunkMetric;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.jqwik.NettyLeaks;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.server.BaseDefaultNodeTest;

/* The container queues every chunk of a request for the resource that reads it, so a body that no resource reads is
   held by the container unless it lets go of it once the request is answered. A default node answers a good many
   requests that way: those to a path that is not there, to one that takes another method, and to a part this release
   leaves off. The pooled buffers that hold such a body have to go back to the pool, and none may be dropped. A body
   that a resource does read has been closed by then, and the answer to it is the one the resource gives. */
public class RestUnreadBodyTest extends BaseDefaultNodeTest {
	private static final int BODY = 512 * 1024;
	private static final int PIECE = 16 * 1024;
	private static final int ROUNDS = 24;

	/* What the event loops keep in their caches of released buffers, which the pool counts as in use */
	private static final long MARGIN = 4L * 1024 * 1024;
	private static final Duration SETTLE = Duration.ofSeconds(15);
	private static final Duration PATIENCE = Duration.ofSeconds(30);
	private static final byte[] CRLF = { '\r', '\n' };
	private static final String STREAM = "Content-Type: application/octet-stream";
	private static final String JSON = "Content-Type: application/json";
	private static final String CHUNKED = "Transfer-Encoding: chunked";

	private static NettyLeaks leaks;

	private record Unread(String name, String request, String type, boolean chunked, String status) {
		@Override
		public String toString() {
			return name;
		}
	}

	/* The watch has to begin before the first buffer of the node is allocated, and the container starts with the
	   first property */
	@BeforeContainer
	private static void watchTheBuffers() throws ReflectiveOperationException {
		leaks = NettyLeaks.watch();
	}

	@AfterContainer
	private static void stopWatchingTheBuffers() {
		leaks.close();
	}

	@Provide
	public Arbitrary<Unread> unreadBodies() {
		return Arbitraries.of(
			new Unread("a path that is not there", "POST /not-there", STREAM, false, "404"),
			new Unread("a path that is not there, chunked", "POST /not-there", STREAM, true, "404"),
			new Unread("a path that takes another method", "POST /version", STREAM, false, "405"),
			new Unread("a storage part left off", "POST /storage", STREAM, false, "503"),
			new Unread("a ledger part left off", "POST /ledger/transaction", JSON, false, "503"),
			new Unread("a body that the resource reads whole and refuses", "POST /gridspork/wire", STREAM, false, "400")
		);
	}

	private SSLSocket connect() throws Exception {
		final SSLContext context = SSLContext.getInstance("TLS");

		context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);

		final SSLSocket socket = (SSLSocket) context.getSocketFactory()
			.createSocket(RestOptions.getHost(), RestOptions.getPort());

		socket.setSoTimeout(Math.toIntExact(PATIENCE.toMillis()));
		return socket;
	}

	private static byte[] head(final Unread unread) {
		final StringBuilder head = new StringBuilder(unread.request()).append(" HTTP/1.1\r\nHost: localhost\r\n")
			.append("Authorization: ").append(BearerTokenFilter.SCHEME).append(' ').append(RestOptions.getToken())
			.append("\r\n").append(unread.type()).append("\r\n");

		head.append(unread.chunked() ? CHUNKED : "Content-Length: " + BODY).append("\r\n\r\n");
		return head.toString().getBytes(StandardCharsets.US_ASCII);
	}

	private static void sendBody(final OutputStream out, final boolean chunked) throws IOException {
		final byte[] data = new byte[PIECE];
		final byte[] size = (Integer.toHexString(PIECE) + "\r\n").getBytes(StandardCharsets.US_ASCII);

		Arrays.fill(data, (byte) ' ');

		for (int sent = 0; sent < BODY; sent += PIECE) {
			if (chunked) {
				out.write(size);
			}

			out.write(data);

			if (chunked) {
				out.write(CRLF);
			}
		}

		if (chunked) {
			out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
		}

		out.flush();
	}

	private static String readLine(final InputStream in) throws IOException {
		final ByteArrayOutputStream line = new ByteArrayOutputStream();

		for (int read = in.read(); read != -1 && read != '\n'; read = in.read()) {
			line.write(read);
		}

		return line.toString(StandardCharsets.US_ASCII).trim();
	}

	/* The status line, with the headers and the body read up to where the next answer on the connection begins */
	private static String statusOf(final InputStream in) throws IOException {
		final String status = readLine(in);
		long length = 0;
		boolean chunked = false;

		for (String header = readLine(in); !header.isEmpty(); header = readLine(in)) {
			final String lower = header.toLowerCase();

			if (lower.startsWith("content-length:")) {
				length = Long.parseLong(header.substring(header.indexOf(':') + 1).trim());
			}

			chunked |= lower.startsWith("transfer-encoding:") && lower.contains("chunked");
		}

		if (chunked) {
			for (int size = Integer.parseInt(readLine(in), 16); size > 0; size = Integer.parseInt(readLine(in), 16)) {
				in.readNBytes(size);
				readLine(in);
			}

			readLine(in);
		} else {
			in.readNBytes(Math.toIntExact(length));
		}

		return status;
	}

	private void send(final SSLSocket socket, final Unread unread) throws IOException {
		final OutputStream out = socket.getOutputStream();

		out.write(head(unread));
		sendBody(out, unread.chunked());

		assertThat(statusOf(socket.getInputStream()), startsWith("HTTP/1.1 " + unread.status()));
	}

	/* A connection of its own for every request, or one that all of them share, since the container keeps what it
	   queued in a stream that belongs to the connection */
	private void burst(final Unread unread, final int rounds, final boolean shared) throws Exception {
		if (shared) {
			try (SSLSocket socket = connect()) {
				for (int round = 0; round < rounds; round++) {
					send(socket, unread);
				}
			}

			return;
		}

		for (int round = 0; round < rounds; round++) {
			try (SSLSocket socket = connect()) {
				send(socket, unread);
			}
		}
	}

	/* The top of the first trace that Netty recorded for a dropped buffer */
	private static String touched(final String record) {
		return record.lines().skip(3).limit(3).map(String::trim).collect(Collectors.joining(" <- "));
	}

	/* The bytes of the pooled direct buffers that are handed out. The used memory that the pool reports counts the
	   whole of every chunk it keeps, so it does not fall when buffers are released. */
	private static long used() {
		long handedOut = 0;

		for (final PoolArenaMetric arena : PooledByteBufAllocator.DEFAULT.metric().directArenas()) {
			for (final PoolChunkListMetric list : arena.chunkLists()) {
				for (final PoolChunkMetric chunk : list) {
					handedOut += chunk.chunkSize() - chunk.freeBytes();
				}
			}
		}

		return handedOut;
	}

	/* The pool is quiet once the event loops have released what the last requests held */
	private static long settled() {
		final AtomicLong last = new AtomicLong(-1);

		await().atMost(SETTLE).until(() -> {
			final long now = used();

			return last.getAndSet(now) == now;
		});

		return last.get();
	}

	/* Netty reports a leak once per distinct trace, so the first body that is dropped is the one that shows */
	private void assertReleased(final Unread unread, final boolean shared) throws Exception {
		burst(unread, 1, shared);

		final long baseline = settled();

		burst(unread, ROUNDS, shared);

		final List<String> dropped = leaks.collect();

		assertThat("Netty found pooled buffers dropped unreleased, the first last touched at "
			+ dropped.stream().findFirst().map(RestUnreadBodyTest::touched).orElse(""), dropped.size(), is(0));

		await().atMost(SETTLE).untilAsserted(() -> {
			final long grown = used() - baseline;

			assertThat("Pooled direct buffers in use grew by " + grown + " bytes after " + ROUNDS + " bodies of "
				+ BODY + " bytes", grown, is(lessThanOrEqualTo(MARGIN)));
		});
	}

	@Property(tries = 6, generation = GenerationMode.EXHAUSTIVE)
	public void shouldReleaseTheBodyOfARequestThatNoResourceReads(@ForAll("unreadBodies") final Unread unread)
		throws Exception {

		assertReleased(unread, false);
	}

	@Property(tries = 6, generation = GenerationMode.EXHAUSTIVE)
	public void shouldReleaseTheBodiesOfRequestsThatShareAConnection(@ForAll("unreadBodies") final Unread unread)
		throws Exception {

		assertReleased(unread, true);
	}
}
