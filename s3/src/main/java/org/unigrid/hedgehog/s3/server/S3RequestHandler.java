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

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.netty.httpserver.NettySecurityContext;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.internal.ContainerUtils;

/*
   Hands each request to Jersey with a body that is read from the socket only as fast as its resource consumes it,
   and a writer that sends the response only as fast as the client takes it. Jersey's own handler queues whole
   bodies in memory and lets its writer block the event loop. A connection serves one request at a time: what a
   client pipelines behind a request waits, unread past what arrived with it, until that request is answered, so
   responses never interleave. For the same reason a request expecting to continue is invited only once it is
   handed to its resource, never in the middle of another response.
*/
@RequiredArgsConstructor
final class S3RequestHandler extends ChannelInboundHandlerAdapter {
	private final URI base;
	private final ApplicationHandler application;
	private final ExecutorService executor;
	private final S3ServerConfig config;
	private final Deque<Object> held = new ArrayDeque<>();

	private ChannelHandlerContext context;
	private S3ReadGate gate;
	private S3RequestBody body;
	private boolean ended;
	private boolean answered;

	private URI uri(final HttpRequest request) {
		final String path = request.uri().startsWith("/") ? request.uri().substring(1) : request.uri();

		return URI.create(base + ContainerUtils.encodeUnsafeCharacters(path));
	}

	/* A request the codec could not parse leaves the connection in no state to read another one */
	private void accept(final HttpRequest request) {
		body = null;

		if (request.decoderResult().isFailure()) {
			context.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				HttpResponseStatus.BAD_REQUEST
			)).addListener(ChannelFutureListener.CLOSE);
			return;
		}

		final ContainerRequest container = new ContainerRequest(base, uri(request), request.method().name(),
			new NettySecurityContext(context), new MapPropertiesDelegate(), application.getConfiguration()
		);
		final S3RequestBody accepted = new S3RequestBody(context.channel(), gate, config.getBodyHighWater(),
			config.getBodyLowWater(), config.getStallTimeout()
		);

		if (HttpUtil.is100ContinueExpected(request)) {
			request.headers().remove(HttpHeaderNames.EXPECT);
			context.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				HttpResponseStatus.CONTINUE
			));
		}

		body = accepted;
		request.headers().names().forEach(name -> container.headers(name, request.headers().getAll(name)));
		container.setEntityStream(accepted);
		container.setWriter(new S3ResponseWriter(context, request, accepted, config.getStallTimeout(),
			() -> context.executor().execute(() -> answered(accepted))
		));
		executor.execute(() -> application.handle(container));
	}

	private void read(final Object message) {
		if (message instanceof HttpRequest request) {
			accept(request);
		}

		if (message instanceof HttpContent content && body != null) {
			body.offer(content.content());

			if (content instanceof LastHttpContent) {
				body.end();
				ended = true;
				gate.hold(this);
				proceed();
			}
		} else {
			ReferenceCountUtil.release(message);
		}
	}

	private void answered(final S3RequestBody answeredBody) {
		if (answeredBody == body) {
			answered = true;
			proceed();
		}
	}

	private void proceed() {
		if (ended && answered) {
			body = null;
			ended = false;
			answered = false;
			gate.release(this);

			while (!ended && !held.isEmpty()) {
				read(held.poll());
			}
		}
	}

	private void fail(final IOException cause) {
		if (body != null) {
			body.fail(cause);
		}
	}

	@Override
	public void handlerAdded(final ChannelHandlerContext context) {
		this.context = context;
		gate = new S3ReadGate(context.channel());
	}

	@Override
	public void channelRead(final ChannelHandlerContext context, final Object message) {
		if (ended) {
			held.add(message);
		} else {
			read(message);
		}
	}

	@Override
	public void channelInactive(final ChannelHandlerContext context) {
		fail(new ClosedChannelException());
		held.forEach(ReferenceCountUtil::release);
		held.clear();
		context.fireChannelInactive();
	}

	@Override
	public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
		fail(cause instanceof IOException failure ? failure : new IOException(cause));
		context.close();
	}
}
