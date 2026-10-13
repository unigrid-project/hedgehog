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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.service.chain.ChainService;

@Sharable
public class JoinChainChannelHandler extends AbstractInboundHandler<JoinChain> {
	public JoinChainChannelHandler() {
		super(JoinChain.class);
	}

	/* A stream that takes no more writes is sent no reply, as the replies to a peer that does not read would pile
	   up in memory: its request times out instead */
	@Override
	public void typedChannelRead(final ChannelHandlerContext ctx, final JoinChain request) {
		CDIUtil.resolveAndRun(ChainService.class, chains -> {
			final JoinReply reply = chains.join(ctx.channel(), request);

			if (ctx.channel().isWritable()) {
				ctx.writeAndFlush(reply).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
			}
		});
	}
}
