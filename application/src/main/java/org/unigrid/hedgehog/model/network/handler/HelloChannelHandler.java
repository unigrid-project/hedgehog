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
import io.netty.incubator.codec.quic.QuicStreamChannel;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import java.net.InetSocketAddress;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.network.ConnectionContainer;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import static org.unigrid.hedgehog.model.network.handler.ConnectionHandler.SOCKET_ADDRESS_KEY;
import org.unigrid.hedgehog.model.network.packet.Hello;

@Slf4j
@Sharable
public class HelloChannelHandler extends AbstractInboundHandler<Hello> {
	public HelloChannelHandler() {
		super(Hello.class);
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, Hello hello) throws Exception {
		final Instance<Topology> topology = CDI.current().select(Topology.class);

		if (topology.isResolvable()) {
			final InetSocketAddress address = ctx.channel().parent().attr(SOCKET_ADDRESS_KEY).get();
			log.atTrace().log("HELLO with port {} from source {}", hello.getPort(), address.getAddress());

			final ConnectionContainer container = ConnectionContainer.builder()
				.channel((QuicStreamChannel) ctx.channel())
				.build();

			topology.get().addNode(Node.builder()
				.address(new InetSocketAddress(address.getAddress(), hello.getPort()))
				.connection(Optional.of(container))
				.build()
			);
		} else {
			log.atWarn().log("Unable to resolve Topology instance");
		}
	}
}
