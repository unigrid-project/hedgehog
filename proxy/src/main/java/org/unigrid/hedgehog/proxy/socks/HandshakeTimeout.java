/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.proxy.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/* A client that connects and then says nothing, or stalls in the middle of its greeting or its command, would hold
   its connection for as long as it likes. The handshake therefore has a deadline from the moment the connection
   exists, however slowly the bytes trickle in, and it ends with the command: the handler sits behind the command
   decoder, so the first message it sees is that command. What follows, the tunnel's answer and the open stream,
   is bounded by the tunnel, not here. */
final class HandshakeTimeout extends ChannelInboundHandlerAdapter {
	private final long millis;
	private ScheduledFuture<?> deadline;

	HandshakeTimeout(long millis) {
		this.millis = millis;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		deadline = ctx.executor().schedule(() -> {
			ctx.close();
		}, millis, TimeUnit.MILLISECONDS);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		deadline.cancel(false);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object command) {
		ctx.pipeline().remove(this);
		ctx.fireChannelRead(command);
	}
}
