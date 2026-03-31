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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.incubator.codec.quic.QuicConnectionEvent;
import io.netty.util.AttributeKey;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;

import java.net.InetAddress;
import java.net.InetSocketAddress;

@ChannelHandler.Sharable
public class ConnectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    public static final AttributeKey<InetSocketAddress> SOCKET_ADDRESS_KEY =
            AttributeKey.valueOf("SOCKET_ADDRESS");

    private int resolvePort(ChannelHandlerContext ctx) {
        InetSocketAddress existing = ctx.channel().attr(SOCKET_ADDRESS_KEY).get();
        NetOptions netOptions = new NetOptions();
        return existing != null ? existing.getPort() : netOptions.getPort();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (!(evt instanceof QuicConnectionEvent event)) {
            ctx.fireUserEventTriggered(evt);
            return;
        }

        Instance<Topology> topologyInstance = CDI.current().select(Topology.class);
        if (!topologyInstance.isResolvable()) {
            log.warn("Topology bean not resolvable");
            return;
        }

        InetAddress inetAddress = ((InetSocketAddress) event.newAddress()).getAddress();
        InetSocketAddress newAddress = new InetSocketAddress(inetAddress.getHostAddress(), resolvePort(ctx));
        InetSocketAddress oldAddress = ctx.channel().attr(SOCKET_ADDRESS_KEY).get();

        if (oldAddress != null) {
            Node node = new Node(oldAddress);
            topologyInstance.get().modifyNode(node, n -> n.setAddress(newAddress));
        }

        ctx.channel().attr(SOCKET_ADDRESS_KEY).set(newAddress);
        log.debug("Channel {} updated address to {}", ctx.channel().id(), newAddress);
    }
}