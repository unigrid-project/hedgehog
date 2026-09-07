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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/* Decoders hand on the frames they do not own, all as the same buffer, which each of them keeps as what it has read
   so far until the buffer is used up. A frame of a type no stage takes would stay there and run into the frames
   after it, so it is used up and released at the end of the pipeline. */
@Sharable
public class UnclaimedFrameHandler extends ChannelInboundHandlerAdapter {
	@Override
	public void channelRead(final ChannelHandlerContext ctx, final Object message) {
		if (message instanceof ByteBuf frame) {
			frame.skipBytes(frame.readableBytes());
			frame.release();
		} else {
			ctx.fireChannelRead(message);
		}
	}
}
