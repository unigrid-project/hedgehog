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

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;

@Slf4j
@Sharable
public class PublishGridnodeChannelHandler extends AbstractInboundHandler<PublishGridnode> {

	public PublishGridnodeChannelHandler() {
		super(PublishGridnode.class);
		log.atDebug().log("Init");

	}

	/* Forwarding only what was accepted ends a flood: the copy that comes back is no longer newer */
	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, PublishGridnode obj) throws Exception {
		CDIUtil.resolveAndRun(Topology.class, topology -> {
			if (topology.offerGridnode(obj.getGridnode())) {
				Topology.sendAllExcept(PublishGridnode.builder().gridnode(obj.getGridnode()).build(), topology,
					topology.getChannels().get(ctx.channel())
				);
			}
		});
	}
}
