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
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.incubator.codec.quic.QuicConnectionEvent;
import io.netty.util.AttributeKey;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;

@Slf4j
@Sharable
public class ConnectionHandler extends ChannelInboundHandlerAdapter {
	public static final AttributeKey<InetSocketAddress> SOCKET_ADDRESS_KEY = AttributeKey.valueOf("SOCKET_ADDRESS");

	private int getPort(ChannelHandlerContext ctx) {
		final InetSocketAddress oldAddress = ctx.channel().attr(SOCKET_ADDRESS_KEY).get();
		return Objects.isNull(oldAddress) ? NetOptions.getPort() : oldAddress.getPort();
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		final Instance<Topology> topology = CDI.current().select(Topology.class);

		if (topology.isResolvable() && evt instanceof QuicConnectionEvent event) {
			log.atDebug().log("New address (or change) detected");

			final InetAddress addr = ((InetSocketAddress) event.newAddress()).getAddress();
			final InetSocketAddress oldWPort = ctx.channel().attr(SOCKET_ADDRESS_KEY).get();
			final InetSocketAddress newWPort = new InetSocketAddress(addr.getHostAddress(), getPort(ctx));

			if (Objects.nonNull(oldWPort)) {
				final Node node = Node.builder().address(oldWPort).build();

				topology.get().modifyNode(node, n -> {
					log.atTrace().log("Modifying node {} with new address {}", n, newWPort);
					n.setAddress(newWPort);
				});
			}

			log.atTrace().log("Stored new address {} on channel {}", newWPort, ctx.channel());
			ctx.channel().attr(SOCKET_ADDRESS_KEY).set(newWPort);
		} else {
			log.atWarn().log("Unable to resolve Topology instance");
		}
	}
}
