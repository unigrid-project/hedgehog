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
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;

@Sharable
public class PublishPeersChannelHandler extends AbstractInboundHandler<PublishPeers> {
	public PublishPeersChannelHandler() {
		super(PublishPeers.class);
	}

	private static void addNewNodeToTopology(Topology topology, Node node) {
		if (!topology.containsNode(node)) {
			topology.addNode(node);
		}
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext context, PublishPeers publishPeers) throws Exception {
		final Instance<Topology> topology = CDI.current().select(Topology.class);

		if (topology.isResolvable()) {
			for (Node newNode : publishPeers.getNodes()) {
				addNewNodeToTopology(topology.get(), newNode);
			}
		}
	}
}
