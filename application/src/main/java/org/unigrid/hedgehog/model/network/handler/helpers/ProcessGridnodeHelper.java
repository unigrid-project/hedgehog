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

package org.unigrid.hedgehog.model.network.handler.helpers;

import io.netty.channel.ChannelHandlerContext;
import java.net.InetSocketAddress;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.GridnodeOptions;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Topology;
import static org.unigrid.hedgehog.model.network.handler.ConnectionHandler.SOCKET_ADDRESS_KEY;

@Slf4j
public class ProcessGridnodeHelper {

	public void processGridnode(ChannelHandlerContext ctx, Gridnode gridnode, Set<Gridnode> existingGridnodes,
		Topology topology) {
		if (shouldUpdateHostname(gridnode)) {
			updateHostnameFromContext(ctx, gridnode);
		}

		if (!existsInTopology(gridnode, existingGridnodes)) {
			addNewGridnodeToTopology(gridnode, topology);
		} else {
			modifyExistingGridnodeInTopology(gridnode, topology);
		}
	}

	private boolean shouldUpdateHostname(Gridnode gridnode) {
		return gridnode.getHostName().contains("0.0.0.0") && !gridnode.getId()
			.equals(GridnodeOptions.getGridnodeKey());
	}

	private void updateHostnameFromContext(ChannelHandlerContext ctx, Gridnode gridnode) {
		final InetSocketAddress address = ctx.channel().parent().attr(SOCKET_ADDRESS_KEY).get();
		if (address != null) {
			log.atDebug().log(address.toString());
			log.atTrace().log("Sending with port {} from source {}", address.getPort(),
				address.getAddress());
			gridnode.setHostName(address.getAddress().getHostAddress() + ":" + address.getPort());
		}
	}

	private boolean existsInTopology(Gridnode gridnode, Set<Gridnode> gridnodes) {
		return gridnodes.stream().anyMatch(g -> g.getId().equals(gridnode.getId()));
	}

	private void addNewGridnodeToTopology(Gridnode gridnode, Topology topology) {
		topology.addGridnode(Gridnode.builder().id(gridnode.getId()).status(gridnode.getStatus())
			.hostName(gridnode.getHostName()).build());
	}

	private void modifyExistingGridnodeInTopology(Gridnode gridnode, Topology topology) {
		topology.modifyGridnode(gridnode, g -> {
			log.atDebug().log("Modifying a gridnode");
			g.setStatus(gridnode.getStatus());
			g.setHostName(gridnode.getHostName());
		});
	}
}
