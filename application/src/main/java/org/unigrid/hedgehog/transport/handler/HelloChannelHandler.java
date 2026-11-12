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

package org.unigrid.hedgehog.transport.handler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import java.net.InetSocketAddress;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.ConnectionContainer;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import static org.unigrid.hedgehog.transport.handler.ConnectionHandler.SOCKET_ADDRESS_KEY;
import org.unigrid.hedgehog.model.network.packet.Hello;

@Slf4j
@Sharable
public class HelloChannelHandler extends AbstractInboundHandler<Hello> {
	public HelloChannelHandler() {
		super(Hello.class);
	}

	/* A connection names one node, by the first port that its peer says. A hello that follows it on any stream of the
	   connection is left unanswered: each would name one more node in a topology that all peers share and that holds
	   no more than MAX_NODES of them, so one peer could fill it alone. */
	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, Hello hello) throws Exception {
		if (RemoteAddress.listensOn(ctx.channel(), hello.getPort())) {
			name(ctx, hello);
		}
	}

	private static void name(ChannelHandlerContext ctx, Hello hello) {
		CDIUtil.resolveAndRun(Topology.class, topology -> {
			final InetSocketAddress address = ctx.channel().parent().attr(SOCKET_ADDRESS_KEY).get();
			log.atTrace().log("HELLO with port {} from source {}", hello.getPort(), address.getAddress());

			final ConnectionContainer container = ConnectionContainer.builder()
				.channel((QuicStreamChannel) ctx.channel())
				.build();

			final Node said = Node.builder()
				.address(new InetSocketAddress(address.getAddress(), hello.getPort()))
				.inbound(Optional.of(container))
				.build();

			/* A node that is known already takes the stream while it holds no open one: it connects again once
			   the stream it opened before closed, or it is one that this node dialed */
			if (!topology.addNode(said)) {
				topology.modifyNode(said, known -> known.setInbound(known.getInbound()
					.filter(inbound -> inbound.getChannel().isActive()).or(said::getInbound)));
			}

			letGoWhenClosed(ctx.channel(), topology);
		});
	}

	/* The listeners of a close are called on the event loop of the stream wherever it closes, which may be inside a
	   call that holds the lock of the topology for reading, where taking it for writing would wait for ever. The node
	   is therefore let go of in a task of its own, which the event loop runs once it holds no lock of the topology. */
	private static void letGoWhenClosed(Channel stream, Topology topology) {
		stream.closeFuture().addListener(closed -> stream.eventLoop()
			.execute(() -> topology.letGoOfClosedStream(stream)));
	}
}
