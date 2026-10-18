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

package org.unigrid.hedgehog.s3.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;

public class S3FlowControlTest {
	private static final long UPLOAD = 256L * 1024 * 1024;
	private static final long ABORTED_AFTER = 8L * 1024 * 1024;
	private static final long DOWNLOAD_BYTES_PER_SECOND = 32L * 1024 * 1024;
	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	/* The high water mark plus what the socket buffers of both ends hold on the loopback interface */
	private static final long MAX_BACKLOG = 32L * 1024 * 1024;

	private static final int CLIENTS = 8;
	private static final int PROBE_TIMEOUT_MILLIS = 2000;
	private static final long TIMEOUT_SECONDS = 30;
	private static final String FORBIDDEN = "HTTP/1.1 403";

	private static NettyLeaks leaks;

	@BeforeContainer
	public static void watchLeaks() throws ReflectiveOperationException {
		leaks = NettyLeaks.watch();
	}

	@AfterContainer
	public static void stopWatchingLeaks() {
		leaks.close();
	}

	private static long readSlowly(final InputStream body) throws IOException, InterruptedException {
		final byte[] block = new byte[PacedResource.BLOCK];
		final long start = System.nanoTime();
		long read = 0;

		for (int count = 0; count >= 0; count = body.read(block)) {
			read += count;
			Thread.sleep(Duration.ofNanos(Math.max(0, start + read * NANOS_PER_SECOND / DOWNLOAD_BYTES_PER_SECOND
				- System.nanoTime())));
		}

		return read;
	}

	/* A request any event loop answers at once, as authentication refuses it before reading anything more */
	private static String probe(final int port) throws IOException {
		try (Socket socket = new Socket(S3Fixture.HOST, port)) {
			socket.setSoTimeout(PROBE_TIMEOUT_MILLIS);
			socket.getOutputStream().write(RawHttp.unsigned("GET", "/", 0));

			return new String(socket.getInputStream().readNBytes(FORBIDDEN.length()), StandardCharsets.ISO_8859_1);
		}
	}

	@Example
	public void pullsAnUploadOnlyAsFastAsItsResourceReadsIt() throws Exception {
		final int port = S3Fixture.freePort();
		final AtomicLong sent = new AtomicLong();
		final PacedResource resource = new PacedResource(sent::get, new CountDownLatch(0));

		try (S3Server server = resource.serve(port); Socket socket = new Socket(S3Fixture.HOST, port)) {
			socket.getOutputStream().write(RawHttp.signed(port, "PUT", "/bucket/large", UPLOAD));
			RawHttp.send(socket.getOutputStream(), UPLOAD, sent);

			assertThat(RawHttp.answers(socket.getInputStream(), 1).getFirst().headers().get(PacedResource.READ),
				equalTo(Long.toString(UPLOAD))
			);
		}

		assertThat(resource.getPeakBuffered().get(),
			lessThanOrEqualTo((long) S3Fixture.config(port).getBodyHighWater() + PacedResource.BLOCK)
		);
		assertThat(resource.getPeakBacklog().get(), lessThan(MAX_BACKLOG));
		assertThat(leaks.collect(), empty());
	}

	@Example
	public void failsTheReadOfAnUploadItsClientAbortedAndFreesItsBuffers() throws Exception {
		final int port = S3Fixture.freePort();
		final PacedResource resource = new PacedResource(() -> 0, new CountDownLatch(0));

		try (S3Server server = resource.serve(port); Socket socket = new Socket(S3Fixture.HOST, port)) {
			socket.getOutputStream().write(RawHttp.signed(port, "PUT", "/bucket/aborted", UPLOAD));
			RawHttp.send(socket.getOutputStream(), ABORTED_AFTER, new AtomicLong());
			socket.setSoLinger(true, 0);
			socket.close();

			assertThat(resource.getFailure().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).buffered(), equalTo(0));
		}

		assertThat(leaks.collect(), empty());
	}

	@Example
	public void holdsBackAResponseForAClientThatReadsSlowly() throws Exception {
		final int port = S3Fixture.freePort();
		final PacedResource resource = new PacedResource(() -> 0, new CountDownLatch(0));

		try (S3Server server = resource.serve(port)) {
			final HttpURLConnection connection = S3Fixture.signed(port, "GET", "/bucket/large");

			try (InputStream body = connection.getInputStream()) {
				assertThat(readSlowly(body), equalTo(PacedResource.DOWNLOAD));
			}
		}

		assertThat(resource.getPeakPending().get(), lessThanOrEqualTo(3L * PacedResource.BLOCK));
		assertThat(leaks.collect(), empty());
	}

	@Example
	public void servesOtherClientsWhileAResponseWaitsForItsResource() throws Exception {
		final int port = S3Fixture.freePort();
		final CountDownLatch resumed = new CountDownLatch(1);
		final PacedResource resource = new PacedResource(() -> 0, resumed);

		try (S3Server server = resource.serve(port)) {
			final HttpURLConnection connection = S3Fixture.signed(port, "GET", "/bucket/stalled");

			try {
				connection.getInputStream();
				assertThat(resource.getStalled().await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));

				for (int client = 0; client < CLIENTS; client++) {
					assertThat(probe(port), startsWith(FORBIDDEN));
				}
			} finally {
				resumed.countDown();
				connection.disconnect();
			}
		}

		assertThat(leaks.collect(), empty());
	}
}
