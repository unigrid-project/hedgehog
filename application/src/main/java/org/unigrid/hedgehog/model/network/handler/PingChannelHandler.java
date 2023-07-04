/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.Ping;

@Sharable
public class PingChannelHandler extends AbstractInboundHandler<Ping> {
	public PingChannelHandler() {
		super(Ping.class);
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, Ping ping) throws Exception {
		if (!ping.isResponse()) {
			ping.setResponse(true);
			ctx.writeAndFlush(ping).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
		} else {
			CDIUtil.resolveAndRun(Topology.class, topology -> {
				topology.getChannels().get(ctx.channel()).ifPresent(n -> {
					final long previousTime = ctx.channel().attr(Ping.PING_TIME_KEY).get();
					n.setNsPing(System.nanoTime() - previousTime);
				});
			});
		}
	}
}
