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

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;

/* A peer that ends the main stream of a connection is done with it: it reads no more of what the node writes there,
   and the end of the stream is all that it says. The stream of the node stays open at its own end, so the node would
   hold the peer as connected, and write to it, until its own pings went unanswered, and for as long as it takes on a
   stream that takes no more, which is sent none. A node that dialed the connection therefore closes it as the stream
   ends, as it does with one whose pings go unanswered, and the next round of the topology dials the peer again, which
   stays in the topology as after any close.

   What arrived before the end is handed on first, and so is the end, to the stages behind this one. The stage is on
   the main stream of a connection that the node dialed and on no other: a connection that a peer made is the
   server's to close (StreamlessConnectionHandler). */
@Sharable
public class EndedStreamHandler extends ChannelInboundHandlerAdapter {
	private static final String ENDED = "ended the stream";

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		super.userEventTriggered(ctx, evt);

		if (evt instanceof ChannelInputShutdownEvent) {
			ConnectionClose.of(ctx.channel(), ENDED);
		}
	}
}
