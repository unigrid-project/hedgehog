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

import io.netty.channel.ChannelHandlerContext;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.Attribute;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.Optional;
import mockit.Expectations;
import mockit.Mocked;
import mockit.Verifications;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.network.ChannelMap;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.Hello;
import org.unigrid.hedgehog.server.TestServer;

@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class HelloChannelHandlerTest extends BaseMockedWeldTest {
	private static final String PEER_HOST = "127.0.100.1";
	private static final int PEER_PORT = 1000;
	private static final int SOURCE_PORT = 1001;

	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;
	@Mocked private QuicChannel connection;

	@Inject private Topology topology;

	@BeforeTry
	public void before() {
		TestServer.mockProperties();
		topology.repopulate();
	}

	private void mockInbound(ChannelHandlerContext ctx, QuicStreamChannel inbound,
		Attribute<InetSocketAddress> sourceAddress) {

		new Expectations() {{
			ctx.channel(); result = inbound;
			inbound.parent(); result = connection;
			connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY); result = sourceAddress;
			sourceAddress.get(); result = new InetSocketAddress(PEER_HOST, SOURCE_PORT);
		}};
	}

	@Example
	public void shouldAddAnUnknownNode(@Mocked ChannelHandlerContext ctx, @Mocked QuicStreamChannel inbound,
		@Mocked Attribute<InetSocketAddress> sourceAddress) throws Exception {

		mockInbound(ctx, inbound, sourceAddress);
		new HelloChannelHandler().typedChannelRead(ctx, Hello.builder().port(PEER_PORT).build());

		final Node node = Node.builder().address(new InetSocketAddress(PEER_HOST, PEER_PORT)).build();
		final Node stored = topology.cloneNodes().stream().filter(node::equals).findFirst().orElseThrow();

		assertThat(topology.containsNode(node), is(true));
		assertThat(stored.getConnection().get().getChannel(), is(inbound));
	}

	@Example
	public void shouldReplaceTheStaleConnectionOfAKnownNode(@Mocked ChannelHandlerContext ctx,
		@Mocked QuicStreamChannel inbound, @Mocked QuicStreamChannel staleChannel,
		@Mocked Attribute<InetSocketAddress> sourceAddress, @Mocked Connection staleConnection) throws Exception {

		final Node known = Node.builder()
			.address(new InetSocketAddress(PEER_HOST, PEER_PORT))
			.connection(Optional.of(staleConnection))
			.build();

		mockInbound(ctx, inbound, sourceAddress);

		new Expectations() {{
			staleConnection.getChannel(); result = staleChannel;
		}};

		topology.addNode(known);
		topology.getChannels().set(staleChannel, known);

		new HelloChannelHandler().typedChannelRead(ctx, Hello.builder().port(PEER_PORT).build());

		new Verifications() {{
			staleConnection.closeDirty(); times = 1;
		}};

		final Node stored = topology.cloneNodes().stream().filter(known::equals).findFirst().orElseThrow();

		assertThat(topology.getChannels().get(staleChannel).isPresent(), is(false));
		assertThat(stored.getConnection().get().getChannel(), is(inbound));
	}

	@Example
	public void shouldKeepTheConnectionThisNodeDialed(@Mocked ChannelHandlerContext ctx,
		@Mocked QuicStreamChannel inbound, @Mocked QuicStreamChannel dialedChannel,
		@Mocked Attribute<InetSocketAddress> sourceAddress, @Mocked Connection dialed) throws Exception {

		final Node known = Node.builder()
			.address(new InetSocketAddress(PEER_HOST, PEER_PORT))
			.connection(Optional.of(dialed))
			.build();

		mockInbound(ctx, inbound, sourceAddress);

		new Expectations() {{
			dialed.isDialed(); result = true;
			dialed.getChannel(); result = dialedChannel; minTimes = 0;
		}};

		topology.addNode(known);
		topology.getChannels().set(dialedChannel, known);

		new HelloChannelHandler().typedChannelRead(ctx, Hello.builder().port(PEER_PORT).build());

		new Verifications() {{
			dialed.closeDirty(); times = 0;
		}};

		final Node stored = topology.cloneNodes().stream().filter(known::equals).findFirst().orElseThrow();

		assertThat(topology.getChannels().get(dialedChannel).isPresent(), is(true));
		assertThat(stored.getConnection().get().getChannel(), is(dialedChannel));
	}
}
