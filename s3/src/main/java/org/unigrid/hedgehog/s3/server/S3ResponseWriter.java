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

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import jakarta.ws.rs.core.Response;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.spi.ContainerResponseWriter;

/*
   Writes a response from the thread that produces it. While the channel holds more than its high water mark, each
   part of the body waits for the previous one to go out, so a client reading slowly holds back the resource instead
   of filling the memory of the server, and the event loop never waits for a resource as it does with Jersey's own
   writer.
*/
@RequiredArgsConstructor
final class S3ResponseWriter implements ContainerResponseWriter {
	private static final int CHUNK = 64 * 1024;

	private final ChannelHandlerContext context;
	private final HttpRequest request;
	private final S3RequestBody body;
	private final Duration stallTimeout;
	private final Runnable answered;
	private boolean started;

	private static HttpResponseStatus status(final Response.StatusType status) {
		return status.getReasonPhrase() == null ? HttpResponseStatus.valueOf(status.getStatusCode())
			: new HttpResponseStatus(status.getStatusCode(), status.getReasonPhrase());
	}

	private boolean isHead() {
		return HttpMethod.HEAD.equals(request.method());
	}

	/* A HEAD response keeps the length of the body it leaves out, when its resource gave one */
	private HttpResponse head(final long length, final ContainerResponse container) {
		final HttpResponse response = new DefaultHttpResponse(request.protocolVersion(),
			status(container.getStatusInfo())
		);

		container.getStringHeaders().forEach(response.headers()::add);

		if (length < 0) {
			HttpUtil.setTransferEncodingChunked(response, true);
		} else if (!isHead() || !response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
			HttpUtil.setContentLength(response, length);
		}

		if (HttpUtil.isKeepAlive(request)) {
			response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		}

		return response;
	}

	@Override
	public synchronized OutputStream writeResponseStatusAndHeaders(final long length,
		final ContainerResponse container) {

		started = true;
		context.write(head(length, container));

		if (isHead() || length == 0) {
			context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
			return null;
		}

		return new BufferedOutputStream(new Body(), CHUNK);
	}

	/* Suspending only keeps a response open, so a suspension that would have to time out is refused */
	@Override
	public boolean suspend(final long timeout, final TimeUnit unit, final TimeoutHandler handler) {
		return timeout <= 0;
	}

	@Override
	public void setSuspendTimeout(final long timeout, final TimeUnit unit) {
		throw new IllegalStateException("A suspended response cannot time out");
	}

	@Override
	public void commit() {
		body.discard();
		answered.run();
	}

	@Override
	public synchronized void failure(final Throwable error) {
		final ChannelFuture answer = started ? context.newSucceededFuture()
			: context.writeAndFlush(new DefaultFullHttpResponse(request.protocolVersion(),
				HttpResponseStatus.INTERNAL_SERVER_ERROR
			));

		answer.addListener(ChannelFutureListener.CLOSE);
		body.discard();
		answered.run();
	}

	@Override
	public boolean enableResponseBuffering() {
		return true;
	}

	private final class Body extends OutputStream {
		private ChannelFuture last = context.newSucceededFuture();

		private boolean isSent() throws InterruptedIOException {
			try {
				return last.await(stallTimeout.toMillis(), TimeUnit.MILLISECONDS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException();
			}
		}

		/* Waiting for the previous write rather than for writability needs no event to wake the writer */
		private void awaitRoom() throws IOException {
			if (!context.channel().isWritable() && !isSent()) {
				context.close();
				throw new IOException("The response stalled for " + stallTimeout.toMillis() + " ms");
			}

			if (last.cause() != null) {
				throw new IOException(last.cause());
			}

			if (!context.channel().isActive()) {
				throw new ClosedChannelException();
			}
		}

		@Override
		public void write(final int octet) throws IOException {
			write(new byte[] {(byte) octet}, 0, 1);
		}

		@Override
		public void write(final byte[] bytes, final int offset, final int length) throws IOException {
			for (int start = offset; start < offset + length; start += CHUNK) {
				final int size = Math.min(CHUNK, offset + length - start);

				awaitRoom();
				last = context.writeAndFlush(new DefaultHttpContent(context.alloc().buffer(size)
					.writeBytes(bytes, start, size)
				));
			}
		}

		@Override
		public void close() throws IOException {
			awaitRoom();
			last = context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
		}
	}
}
