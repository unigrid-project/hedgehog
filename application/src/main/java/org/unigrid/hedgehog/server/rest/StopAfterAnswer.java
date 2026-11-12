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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.unigrid.hedgehog.model.cdi.CDIContext;

/* The server closes every connection as the node shuts down, and an answer that is not written by then is lost: the
   caller that stopped the node is left without one and takes the node for one that it could not reach. The shutdown
   therefore begins only once the answer to that call is written. The resource cannot tell when that is, as the
   container hands its answer to the connection on a thread of its own and does not wait for it, so it is told here,
   where the answer passes on its way to the socket. Written means handed to the socket: whether the caller has read
   it nobody knows. A caller that is gone or does not read must not keep the node up, so the shutdown begins after a
   wait at the latest. It begins once, with whichever of the two comes first. */
final class StopAfterAnswer extends ChannelOutboundHandlerAdapter {
	public static final Duration PATIENCE = Duration.ofSeconds(2);

	/* Set on a connection from when the node is told to stop over it until the shutdown begins, and holds what
	   begins the shutdown at the latest */
	private static final AttributeKey<Future<?>> LATEST = AttributeKey.valueOf("hedgehog.rest.stop.latest");

	private boolean answering;

	/* The resource asks for the stop before it answers, so the answer that begins after this is its answer */
	static void await(final Channel connection) {
		connection.attr(LATEST).set(connection.eventLoop().schedule(() -> stop(connection),
			PATIENCE.toMillis(), TimeUnit.MILLISECONDS));
	}

	/* An answer whose write fails is over as well, and nothing is left to wait for. One for a connection that has
	   closed before it is written does not get this far, and the node is told when the wait is over. */
	@Override
	public void write(final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise) {
		final Channel connection = ctx.channel();

		ctx.write(message, ends(connection, message)
			? promise.unvoid().addListener(written -> stop(connection)) : promise);
	}

	/* An answer begins with its head and is over with its end. The container ends an answer without a body twice,
	   with the answer itself and with an empty end after it, which the codec refuses at once whether or not the
	   answer is written, so an end counts only for an answer that has begun. */
	private boolean ends(final Channel connection, final Object message) {
		if (message instanceof HttpResponse && connection.attr(LATEST).get() != null) {
			answering = true;
		}

		if (!answering || !(message instanceof LastHttpContent)) {
			return false;
		}

		answering = false;
		return true;
	}

	private static void stop(final Channel connection) {
		final Future<?> latest = connection.attr(LATEST).getAndSet(null);

		if (latest != null) {
			latest.cancel(false);
			CDIContext.stop();
		}
	}
}
