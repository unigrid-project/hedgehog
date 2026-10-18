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

import io.netty.handler.codec.http.HttpHeaderNames;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.s3.jqwik.Expect;

public class S3ConnectionTest {
	private static final int OK = 200;
	private static final int FORBIDDEN = 403;
	private static final long SMALL_REST = 64 * 1024;
	private static final long ENDLESS = 1L << 40;
	private static final long OVERRUN = 64L * 1024 * 1024;
	private static final int TIMEOUT_MILLIS = 30_000;

	/* Ample time to answer a request that was not held back behind the one before it */
	private static final Duration MEANWHILE = Duration.ofMillis(500);

	private static NettyLeaks leaks;

	@BeforeContainer
	public static void watchLeaks() throws ReflectiveOperationException {
		leaks = NettyLeaks.watch();
	}

	@AfterContainer
	public static void stopWatchingLeaks() {
		leaks.close();
	}

	private static Socket connect(final int port) throws IOException {
		final Socket socket = new Socket(S3Fixture.HOST, port);

		socket.setSoTimeout(TIMEOUT_MILLIS);
		return socket;
	}

	@Example
	public void answersPipelinedRequestsCompletelyAndInOrder() throws Exception {
		final int port = S3Fixture.freePort();
		final CountDownLatch resumed = new CountDownLatch(1);
		final PacedResource resource = new PacedResource(() -> 0, resumed);

		try (S3Server server = resource.serve(port); Socket socket = connect(port)) {
			final ByteArrayOutputStream requests = new ByteArrayOutputStream();

			requests.writeBytes(RawHttp.signed(port, "GET", "/bucket/stalled", 0));
			requests.writeBytes(RawHttp.unsigned("GET", "/", 0));
			socket.getOutputStream().write(requests.toByteArray());

			assertThat(resource.getStalled().await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), is(true));
			Thread.sleep(MEANWHILE);
			resumed.countDown();

			final List<RawHttp.Answer> answers = RawHttp.answers(socket.getInputStream(), 2);

			assertThat(answers.stream().map(RawHttp.Answer::complete).toList(), contains(true, true));
			assertThat(answers.stream().map(RawHttp.Answer::status).toList(), contains(OK, FORBIDDEN));
			assertThat(answers.getFirst().length(), equalTo((int) PacedResource.STALLED_DOWNLOAD));
		}

		assertThat(leaks.collect(), empty());
	}

	@Example
	public void drainsTheSmallRestOfAnUnreadBodyToServeTheNextRequest() throws Exception {
		final int port = S3Fixture.freePort();
		final PacedResource resource = new PacedResource(() -> 0, new CountDownLatch(0));

		try (S3Server server = resource.serve(port); Socket socket = connect(port)) {
			final OutputStream out = socket.getOutputStream();
			final InputStream in = socket.getInputStream();

			out.write(RawHttp.unsigned("PUT", "/bucket/refused", SMALL_REST));

			final RawHttp.Answer refusal = RawHttp.answers(in, 1).getFirst();

			assertThat(refusal.status(), equalTo(FORBIDDEN));
			assertThat(refusal.headers().get(HttpHeaderNames.CONNECTION), equalTo("keep-alive"));

			RawHttp.send(out, SMALL_REST, new AtomicLong());
			out.write(RawHttp.unsigned("GET", "/", 0));

			assertThat(RawHttp.answers(in, 1).getFirst().status(), equalTo(FORBIDDEN));
		}

		assertThat(leaks.collect(), empty());
	}

	@Example
	public void closesAConnectionRatherThanDrainALargeUnreadBody() throws Exception {
		final int port = S3Fixture.freePort();
		final PacedResource resource = new PacedResource(() -> 0, new CountDownLatch(0));

		try (S3Server server = resource.serve(port); Socket socket = connect(port)) {
			socket.getOutputStream().write(RawHttp.unsigned("PUT", "/bucket/refused", ENDLESS));

			final RawHttp.Answer refusal = RawHttp.answers(socket.getInputStream(), 1).getFirst();

			assertThat(refusal.status(), equalTo(FORBIDDEN));
			assertThat(refusal.headers().get(HttpHeaderNames.CONNECTION), equalTo("close"));
			Expect.assertThrows(IOException.class, () -> RawHttp.send(socket.getOutputStream(), OVERRUN,
				new AtomicLong()
			));
		}

		assertThat(leaks.collect(), empty());
	}
}
