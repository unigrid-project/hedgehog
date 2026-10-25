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
import java.util.Objects;
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
			/* Only an answer to the ping this node sent counts, and only once: a peer may answer what was never
			   asked, or answer twice. The answer starts the count of missed pings again (PingSchedule). */
			final Long sentAt = ctx.channel().attr(Ping.PING_TIME_KEY).getAndSet(null);

			if (Objects.nonNull(sentAt)) {
				ctx.channel().attr(Ping.MISSED_PINGS_KEY).set(0);
				CDIUtil.resolveAndRun(Topology.class, topology -> topology.getChannels().get(ctx.channel())
					.ifPresent(node -> node.setNsPing(System.nanoTime() - sentAt)));
			}
		}
	}
}
