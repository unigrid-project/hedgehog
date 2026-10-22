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
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;

@Slf4j
@Sharable
public class PublishPeersChannelHandler extends AbstractInboundHandler<PublishPeers> {
	private static final List<Predicate<InetAddress>> NOT_PUBLIC = List.of(InetAddress::isLoopbackAddress,
		InetAddress::isSiteLocalAddress, InetAddress::isLinkLocalAddress, InetAddress::isAnyLocalAddress,
		InetAddress::isMulticastAddress, PublishPeersChannelHandler::isUniqueLocal);

	public PublishPeersChannelHandler() {
		super(PublishPeers.class);
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext context, PublishPeers publishPeers) throws Exception {
		final boolean fromPublicPeer = RemoteAddress.of(context.channel())
			.map(sender -> isPublic(sender.getAddress())).orElse(true);

		CDIUtil.resolveAndRun(Topology.class, topology -> {
			log.atTrace().log("Received {} peers from {}",
				publishPeers.getNodes().size(), context.channel().remoteAddress()
			);

			for (Node newNode : publishPeers.getNodes()) {
				if (!fromPublicPeer || !newNode.getAddress().isUnresolved()
					&& isPublic(newNode.getAddress().getAddress())) {
					topology.addNode(newNode);
				}
			}
		});
	}

	/* A peer on the internet that tells of an address on this host or on a private network is either wrong or would
	   have the node connect to what lies behind its own firewall. The peers of a private network, on one host or
	   one LAN, tell each other of such addresses, and are heard. */
	static boolean isPublic(InetAddress address) {
		return NOT_PUBLIC.stream().noneMatch(kind -> kind.test(address));
	}

	/* IPv6 fc00::/7, which Java has no test of its own for */
	private static boolean isUniqueLocal(InetAddress address) {
		return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
	}
}
