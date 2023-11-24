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

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;

@Slf4j
@Sharable
public class PublishGridnodeChannelHandler extends AbstractInboundHandler<PublishGridnode> {

	public PublishGridnodeChannelHandler() {
		super(PublishGridnode.class);
		log.atDebug().log("Init");

	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, PublishGridnode obj) throws Exception {
		CDIUtil.resolveAndRun(Topology.class, topology -> {
			Set<Gridnode> gridnodes = topology.cloneGridnode();
			boolean isEmpty = false;
			System.out.println(gridnodes.size());
			//Optional<Gridnode> gridnode = gridnodes.stream().filter(g -> obj.getGridnode().equals(g
			//	.getHostName())).findFirst();
			Gridnode gridnode = Gridnode.builder().build();
			for(Gridnode g : gridnodes) {
				if (g.equals(obj.getGridnode())) {
					gridnode = g;
				}
			}
			
			if (gridnodes.isEmpty()) {
				topology.addGridnode(obj.getGridnode());
				gridnode = obj.getGridnode();
				isEmpty = true;
			}

			topology.addGridnode(obj.getGridnode());

			topology.modifyGridnode(gridnode, g -> {
				log.atDebug().log("Modifying a gridnode");
				g.setHostName(obj.getGridnode().getHostName());
				g.setId(obj.getGridnode().getId());
				g.setStatus(obj.getGridnode().getStatus());
			});
			//TODO: propagate if not in list already
			if (isEmpty) {
				log.atDebug().log("gridnode handler send gridnode agien");
				Topology.sendAll(PublishGridnode.builder().gridnode(gridnode).build(),
					topology, Optional.empty());
			}
		});
	}
}
