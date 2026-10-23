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

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.network.codec.SporkWire;

/* The container queues every chunk of a request until a resource reads it, and puts no bound on a chunked body, so a
   request is counted here as it arrives, before the container sees it. One that goes past its limit, or does not
   finish in time, is answered at once and its connection closed. A request without the token may hold nothing at
   all: the filter of the container would refuse it only once the container has begun to queue its body, so it is
   refused here, at its head, in the same way. The close waits a little, since a connection closed with unread data
   is reset, which would take the answer with it, and what the sender goes on sending in the meantime is dropped as
   it comes. */
@RequiredArgsConstructor
final class RequestBodyLimit extends ChannelDuplexHandler {
	/* The body of any resource but the storage uploads and a signed spork is a few kilobytes at most, a transaction
	   of the ledger among them, so a megabyte holds every one of them with room to spare */
	public static final long MAX_REQUEST_BYTES = 1L << 20;

	/* A sender that trickles holds a thread of the container in its read for as long as the body is open */
	public static final Duration BODY_TIME = Duration.ofSeconds(30);
	public static final Duration LINGER = Duration.ofSeconds(2);

	private static final String STORAGE = "/storage";
	private static final String STORAGE_OBJECTS = "/storage-object/";

	/* A signed spork is taken up to the size of the largest one a peer may send, as it reaches the peers whole */
	private static final String SPORK = "/gridspork/wire";

	private final BearerTokenFilter authentication;
	private final long limit;
	private final long uploadLimit;
	private final Duration bodyTime;
	private final Duration linger;

	private long allowed;
	private long remaining;
	private boolean unfinished;
	private boolean answered;
	private boolean refused;
	private Future<?> deadline;

	/* A store takes a body that is far larger than anything else, and a store is not bound by time either, since
	   it takes as long as the sender needs */
	private static boolean isUpload(final String path) {
		return STORAGE.equals(path) || path.startsWith(STORAGE_OBJECTS);
	}

	private long limitOf(final String path) {
		if (isUpload(path)) {
			return uploadLimit;
		}

		return SPORK.equals(path) ? Math.max(limit, SporkWire.MAX_SIZE) : limit;
	}

	@Override
	public void channelRead(final ChannelHandlerContext ctx, final Object message) {
		if (refused) {
			ReferenceCountUtil.release(message);
			return;
		}

		if (message instanceof HttpRequest request && !begin(ctx, request)) {
			ReferenceCountUtil.release(message);
			return;
		}

		if (message instanceof HttpContent content && !count(ctx, content)) {
			ReferenceCountUtil.release(message);
			return;
		}

		ctx.fireChannelRead(message);
	}

	private boolean begin(final ChannelHandlerContext ctx, final HttpRequest request) {
		answered = false;

		if (!authentication.isAuthorized(request.headers().get(HttpHeaderNames.AUTHORIZATION))) {
			refuse(ctx, unauthorized());
			return false;
		}

		return limitBody(ctx, request);
	}

	private boolean limitBody(final ChannelHandlerContext ctx, final HttpRequest request) {
		final String path = new QueryStringDecoder(request.uri()).path();
		final boolean upload = isUpload(path);
		final long declared = HttpUtil.getContentLength(request, 0);

		allowed = limitOf(path);
		remaining = allowed;

		if (declared > allowed) {
			refuse(ctx, response(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, tooLarge()));
			return false;
		}

		unfinished = true;

		if (!upload && (declared > 0 || HttpUtil.isTransferEncodingChunked(request))) {
			deadline = ctx.executor().schedule(() -> refuse(ctx, response(HttpResponseStatus.REQUEST_TIMEOUT,
				"A request has to arrive within " + bodyTime.toSeconds() + " seconds")), bodyTime.toMillis(),
				TimeUnit.MILLISECONDS);
		}

		return true;
	}

	private boolean count(final ChannelHandlerContext ctx, final HttpContent content) {
		remaining -= content.content().readableBytes();

		if (remaining < 0) {
			refuse(ctx, response(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, tooLarge()));
			return false;
		}

		if (content instanceof LastHttpContent) {
			unfinished = false;
			cancelDeadline();
		}

		return true;
	}

	private String tooLarge() {
		return "A request is at most " + allowed + " bytes";
	}

	private void refuse(final ChannelHandlerContext ctx, final HttpResponse response) {
		refused = true;
		cancelDeadline();

		if (answered) {
			ReferenceCountUtil.release(response);
		} else {
			ctx.writeAndFlush(response);
		}

		if (unfinished) {
			ctx.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
		}

		ctx.executor().schedule((Runnable) ctx::close, linger.toMillis(), TimeUnit.MILLISECONDS);
	}

	private static HttpResponse response(final HttpResponseStatus status, final String reason) {
		final byte[] body = ("{\"error\":\"" + reason + "\"}").getBytes(StandardCharsets.UTF_8);
		final DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
			Unpooled.wrappedBuffer(body));

		response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
			.set(HttpHeaderNames.CONTENT_LENGTH, body.length)
			.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

		return response;
	}

	/* What the filter of the container answers, so that a sender is refused alike wherever the refusal comes from */
	private static HttpResponse unauthorized() {
		final DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
			HttpResponseStatus.UNAUTHORIZED);

		response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, BearerTokenFilter.SCHEME)
			.set(HttpHeaderNames.CONTENT_LENGTH, 0)
			.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

		return response;
	}

	/* What the container writes after a refusal answers a request that was cut short, and the sender has had its
	   answer already */
	@Override
	public void write(final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise) {
		if (refused) {
			ReferenceCountUtil.release(message);
			promise.setSuccess();
			return;
		}

		answered |= message instanceof HttpResponse;
		ctx.write(message, promise);
	}

	@Override
	public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
		cancelDeadline();
		super.channelInactive(ctx);
	}

	private void cancelDeadline() {
		if (deadline != null) {
			deadline.cancel(false);
		}
	}
}
