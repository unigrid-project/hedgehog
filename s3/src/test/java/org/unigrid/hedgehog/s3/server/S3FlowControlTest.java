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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.s3.auth.SdkSigner;
import org.unigrid.hedgehog.s3.auth.SigV4;
import org.unigrid.hedgehog.s3.spi.MemoryBlobStore;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

/*
   Uploads go through a raw socket rather than the SDK, whose HTTP client logs every byte it sends at the debug
   level the tests run with, which makes it slower than the paced resource it should outrun.
*/
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
	private static final String END_OF_HEAD = "\r\n\r\n";

	private static NettyLeaks leaks;

	@BeforeContainer
	public static void watchLeaks() throws ReflectiveOperationException {
		leaks = NettyLeaks.watch();
	}

	@AfterContainer
	public static void stopWatchingLeaks() {
		leaks.close();
	}

	private static S3Server start(final int port, final PacedResource resource) throws Exception {
		final S3ServerConfig config = S3Fixture.config(port);

		return S3Server.start(config, S3Server.resources(config, new MemoryBlobStore(), S3Fixture.credentials())
			.register(resource).register(PacedResource.RawBody.class)
		);
	}

	/* Sends the signed head of an upload with an unsigned body, leaving the body to the caller */
	private static Socket startUpload(final int port, final String path, final long length) throws IOException {
		final URI uri = S3Fixture.endpoint(port).resolve(path);
		final SdkHttpRequest request = AwsV4HttpSigner.create().sign(signing -> signing
			.identity(AwsCredentialsIdentity.create(S3Fixture.ACCESS_KEY, S3Fixture.SECRET_KEY))
			.request(SdkHttpRequest.builder().uri(uri).method(SdkHttpMethod.PUT)
				.putHeader("Content-Length", Long.toString(length)).build())
			.putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, SigV4.SERVICE)
			.putProperty(AwsV4HttpSigner.REGION_NAME, SdkSigner.REGION)
			.putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)
		).request();
		final StringBuilder head = new StringBuilder("PUT " + uri.getRawPath() + " HTTP/1.1\r\n");
		final Socket socket = new Socket(S3Fixture.HOST, port);

		request.headers().forEach((name, values) -> head.append(name).append(": ").append(values.getFirst())
			.append("\r\n")
		);
		socket.getOutputStream().write(head.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
		return socket;
	}

	private static void send(final OutputStream body, final long length, final AtomicLong sent) throws IOException {
		final byte[] block = new byte[PacedResource.BLOCK];

		while (sent.get() < length) {
			body.write(block);
			sent.addAndGet(block.length);
		}
	}

	private static String head(final InputStream response) throws IOException {
		final ByteArrayOutputStream head = new ByteArrayOutputStream();

		while (!head.toString(StandardCharsets.ISO_8859_1).endsWith(END_OF_HEAD)) {
			final int octet = response.read();

			if (octet < 0) {
				throw new EOFException(head.toString(StandardCharsets.ISO_8859_1));
			}

			head.write(octet);
		}

		return head.toString(StandardCharsets.ISO_8859_1);
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
			socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + S3Fixture.HOST + END_OF_HEAD)
				.getBytes(StandardCharsets.ISO_8859_1));

			return new String(socket.getInputStream().readNBytes(FORBIDDEN.length()), StandardCharsets.ISO_8859_1);
		}
	}

	@Example
	public void pullsAnUploadOnlyAsFastAsItsResourceReadsIt() throws Exception {
		final int port = S3Fixture.freePort();
		final AtomicLong sent = new AtomicLong();
		final PacedResource resource = new PacedResource(sent::get, new CountDownLatch(0));

		try (S3Server server = start(port, resource); Socket socket = startUpload(port, "/bucket/large", UPLOAD)) {
			send(socket.getOutputStream(), UPLOAD, sent);

			assertThat(head(socket.getInputStream()), containsString(PacedResource.READ + ": " + UPLOAD));
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

		try (S3Server server = start(port, resource); Socket socket = startUpload(port, "/bucket/aborted", UPLOAD)) {
			send(socket.getOutputStream(), ABORTED_AFTER, new AtomicLong());
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

		try (S3Server server = start(port, resource)) {
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

		try (S3Server server = start(port, resource)) {
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
