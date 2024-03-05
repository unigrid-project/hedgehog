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
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.GridnodeOptions;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Topology;
import static org.unigrid.hedgehog.model.network.handler.ConnectionHandler.SOCKET_ADDRESS_KEY;
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
			boolean isEmpty = true;
			//System.out.println(gridnodes.size());

			if (obj.getGridnode().getHostName().contains("0.0.0.0")
				&& !obj.getGridnode().getId().equals(GridnodeOptions.getGridnodeKey())) {
				final InetSocketAddress address = ctx.channel().parent().attr(SOCKET_ADDRESS_KEY).get();

				if (address != null) {
					log.atDebug().log(address.toString());
					log.atTrace().log("Seding with port {} from source {}",
						address.getPort(), address.getAddress());
					obj.getGridnode().setHostName(address.getAddress().getHostAddress()
						+ ":" + address.getPort());
				}
			}

			for (Gridnode g: gridnodes) {
				if (g.getId().equals(obj.getGridnode().getId())) {
					isEmpty = false;
				}
			}

			if (isEmpty) {
				topology.addGridnode(Gridnode.builder().id(obj.getGridnode().getId())
					.status(obj.getGridnode().getStatus())
					.hostName(obj.getGridnode().getHostName()).build());
				Topology.sendAll(PublishGridnode.builder().gridnode(obj.getGridnode()).build(),
					topology, Optional.empty());
			} else {
				topology.modifyGridnode(obj.getGridnode(), g -> {
					log.atDebug().log("Modifying a gridnode");
					g.setStatus(obj.getGridnode().getStatus());
					g.setHostName(obj.getGridnode().getHostName());
				});
			}
		});
	}
}
