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

package org.unigrid.hedgehog.transport.handler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.Future;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/* A peer that is done with the node ends its streams, and the connection it leaves is of no use to either of them. The
   server still held such a connection, and the place it takes among those of its address, until it idled out a
   quarter of an hour later: a peer that crashed says nothing, and the codec does not close a connection that the
   peer said it closed before one of its own timers runs. So a connection that holds no stream is closed after a wait
   in which a peer that stays opens one, as it does right after it connects. The wait starts with the connection, so
   one whose handshake never ends goes the same way.

   One for each connection, as it counts the streams of its own. A stream runs on the event loop of its connection,
   so the count is only touched there. */
@Slf4j
public class StreamlessConnectionHandler extends ChannelInboundHandlerAdapter {
	public static final int WAIT_SECONDS = 20;

	private int streams;
	private Future<?> close;

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		ctx.channel().closeFuture().addListener(closed -> keep());
		closeAfterTheWait(ctx);
	}

	/* A stream that the peer opened is read off its connection, and set up by what follows this handler */
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Channel stream) {
			streams++;
			keep();
			stream.closeFuture().addListener(closed -> ended(ctx));
		}

		ctx.fireChannelRead(msg);
	}

	private void ended(ChannelHandlerContext ctx) {
		streams--;

		if (streams == 0 && ctx.channel().isOpen() && !ctx.executor().isShuttingDown()) {
			closeAfterTheWait(ctx);
		}
	}

	private void closeAfterTheWait(ChannelHandlerContext ctx) {
		close = ctx.executor().schedule(() -> closeNow(ctx), WAIT_SECONDS, TimeUnit.SECONDS);
	}

	private static void closeNow(ChannelHandlerContext ctx) {
		log.atDebug().addArgument(() -> Optional.<Object>ofNullable(ctx.channel()
			.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).get()).orElse(ctx.channel().remoteAddress()))
			.addArgument(WAIT_SECONDS).log("Closed the connection to {}, which held no stream for {} seconds");
		ctx.close();
	}

	private void keep() {
		if (Objects.nonNull(close)) {
			close.cancel(false);
			close = null;
		}
	}
}
