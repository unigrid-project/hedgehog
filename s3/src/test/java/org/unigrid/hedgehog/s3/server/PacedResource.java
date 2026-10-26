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

import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import jakarta.annotation.Priority;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.glassfish.jersey.netty.httpserver.NettySecurityContext;
import org.unigrid.hedgehog.s3.http.Payload;
import org.unigrid.hedgehog.s3.spi.MemoryBlobStore;

/*
   Reads uploads at a fixed pace and writes downloads as fast as the server lets it, noting meanwhile how much the
   server holds on its behalf. A download can instead stall after its first block until the test resumes it, and
   then end after its second.
*/
@Path("/{bucket}/{key:.+}")
@RequiredArgsConstructor
public class PacedResource {
	public static final String READ = "x-paced-read";
	public static final int BLOCK = 64 * 1024;
	public static final long DOWNLOAD = 64L * 1024 * 1024;
	public static final long STALLED_DOWNLOAD = 2L * BLOCK;

	private static final long BYTES_PER_SECOND = 64L * 1024 * 1024;
	private static final long NANOS_PER_SECOND = 1_000_000_000L;
	private static final String RAW_BODY = PacedResource.class.getName();

	private final LongSupplier sent;
	private final CountDownLatch resumed;
	@Getter private final CountDownLatch stalled = new CountDownLatch(1);
	@Getter private final AtomicLong peakBuffered = new AtomicLong();
	@Getter private final AtomicLong peakBacklog = new AtomicLong();
	@Getter private final AtomicLong peakPending = new AtomicLong();
	@Getter private final CompletableFuture<Failure> failure = new CompletableFuture<>();

	public record Failure(IOException cause, int buffered) { }

	/* Runs before authentication replaces the body with its decoded form, so the resource can watch the raw one */
	@PreMatching
	@Priority(Priorities.AUTHENTICATION - 1)
	public static class RawBody implements ContainerRequestFilter {
		@Override
		public void filter(final ContainerRequestContext request) {
			request.setProperty(RAW_BODY, request.getEntityStream());
		}
	}

	public S3Server serve(final int port) throws Exception {
		final S3ServerConfig config = S3Fixture.config(port);

		return S3Server.start(config, S3Server.resources(config, new MemoryBlobStore(), S3Fixture.credentials(),
			Clock.systemUTC()).register(this).register(RawBody.class)
		);
	}

	@PUT
	public Response put(@Context ContainerRequestContext request, InputStream body) throws IOException,
		InterruptedException {

		final InputStream raw = (InputStream) request.getProperty(RAW_BODY);
		final byte[] block = new byte[BLOCK];
		final long start = System.nanoTime();
		long read = 0;

		try {
			for (int count = 0; count >= 0; count = body.read(block)) {
				read += count;
				Thread.sleep(Duration.ofNanos(Math.max(0, start + read * NANOS_PER_SECOND / BYTES_PER_SECOND
					- System.nanoTime())));
				peakBuffered.accumulateAndGet(raw.available(), Math::max);
				peakBacklog.accumulateAndGet(sent.getAsLong() - read, Math::max);
			}
		} catch (IOException ex) {
			failure.complete(new Failure(ex, raw.available()));
			throw ex;
		}

		Payload.of(request).finish();
		return Response.ok().header(READ, read).build();
	}

	@GET
	public StreamingOutput get(@Context ContainerRequestContext request) {
		final Channel channel = ((NettySecurityContext) request.getSecurityContext()).getNettyContext().channel();
		final long length = resumed.getCount() > 0 ? STALLED_DOWNLOAD : DOWNLOAD;

		return output -> {
			final byte[] block = new byte[BLOCK];

			for (long written = 0; written < length; written += BLOCK) {
				peakPending.accumulateAndGet(pending(channel), Math::max);
				output.write(block);
				stallOnce(output);
			}
		};
	}

	private static long pending(final Channel channel) {
		final ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();

		return buffer == null ? 0 : buffer.totalPendingWriteBytes();
	}

	private void stallOnce(final OutputStream output) throws IOException {
		if (resumed.getCount() > 0 && stalled.getCount() > 0) {
			output.flush();
			stalled.countDown();

			try {
				resumed.await();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException();
			}
		}
	}
}
