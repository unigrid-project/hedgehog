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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.stream.IntStream;
import mockit.Delegate;
import mockit.Expectations;
import mockit.Mocked;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.option.RestOptions;
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
import org.unigrid.hedgehog.service.ledger.LedgerStreams;

@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class HelloChannelHandlerTest extends BaseMockedWeldTest {
	private static final String LOCAL = "127.0.0.1";
	private static final int ASSUMED_PORT = 52884;
	private static final int EPHEMERAL_PORT = 40123;
	private static final int FLOOD = 1100;
	private static final int FLOOD_FIRST_PORT = 10000;
	private static final int HONEST_PORT = 30000;

	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;
	@Mocked private QuicStreamChannel stream;
	@Mocked private QuicChannel quic;
	@Mocked private ChannelHandlerContext context;

	@Inject private Topology topology;

	private EmbeddedChannel connection;

	/* The connection that the stream of the examples belongs to from now on, on which no one has said hello yet */
	private void connectAnew() {
		connection = new EmbeddedChannel();
		connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).set(new InetSocketAddress(LOCAL, ASSUMED_PORT));
		connection.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).set(new InetSocketAddress(LOCAL, EPHEMERAL_PORT));
	}

	/* The stream closes with the connection that it belongs to, and runs its tasks on the event loop of that one */
	@BeforeTry
	public void connectFromThisHost() {
		TestServer.mockProperties();
		connectAnew();

		new Expectations() {{
			context.channel(); result = stream; minTimes = 0;
			stream.parent(); result = quic; minTimes = 0;
			quic.attr((AttributeKey<Object>) any); minTimes = 0;
			result = new Delegate<Attribute<Object>>() {
				Attribute<Object> attr(AttributeKey<Object> key) {
					return connection.attr(key);
				}
			};
			stream.closeFuture(); minTimes = 0;
			result = new Delegate<ChannelFuture>() {
				ChannelFuture closeFuture() {
					return connection.closeFuture();
				}
			};
			stream.eventLoop(); minTimes = 0;
			result = new Delegate<EventLoop>() {
				EventLoop eventLoop() {
					return connection.eventLoop();
				}
			};
		}};
	}

	@Property(tries = 10)
	public void namesTheNodeThatSaidHelloByThePortItListensOn(@ForAll @IntRange(min = 1, max = 65535) int port)
		throws Exception {

		assertThat(RemoteAddress.of(stream), equalTo(Optional.of(new InetSocketAddress(LOCAL, EPHEMERAL_PORT))));

		new HelloChannelHandler().typedChannelRead(context, Hello.builder().port(port).build());

		assertThat(RemoteAddress.of(stream), equalTo(Optional.of(new InetSocketAddress(LOCAL, port))));
		assertThat(forgetNodeAt(port), is(true));
	}

	/* The stream that a peer said hello on is sent on, and is no connection that this node dialed, so the node that
	   said it is still one to dial */
	@Example
	public void keepsTheStreamOfAHelloApartFromTheConnectionsThisNodeDialed() throws Exception {
		new HelloChannelHandler().typedChannelRead(context, Hello.builder().port(ASSUMED_PORT + 3).build());

		final Node said = topology.cloneNodes().stream().filter(node -> node.getAddress().getPort() == ASSUMED_PORT + 3)
			.findFirst().orElseThrow();

		assertThat(said.getConnection(), equalTo(Optional.empty()));
		assertThat(said.getInbound().isPresent(), is(true));
		assertThat(said.link(), equalTo(said.getInbound()));
		forgetNodeAt(ASSUMED_PORT + 3);
	}

	@Example
	public void keepsTheFirstHelloOfAConnection() throws Exception {
		final HelloChannelHandler handler = new HelloChannelHandler();

		handler.typedChannelRead(context, Hello.builder().port(ASSUMED_PORT + 1).build());
		handler.typedChannelRead(context, Hello.builder().port(ASSUMED_PORT + 2).build());

		assertThat(RemoteAddress.of(stream), equalTo(Optional.of(new InetSocketAddress(LOCAL, ASSUMED_PORT + 1))));
		forgetNodeAt(ASSUMED_PORT + 1);
		forgetNodeAt(ASSUMED_PORT + 2);
	}

	/* A node that connects again says hello on a new stream, which is sent on once the one it opened before closed, and
	   a node that this node dialed takes it as well; a stream that is still open stays the one. Each of the three nodes
	   says hello on a connection of its own, as a connection names one node. */
	@Example
	public void attachesTheStreamOfAHelloToAKnownNodeWhoseStreamClosedOrThatHeldNone() throws Exception {
		final EmbeddedChannel closed = new EmbeddedChannel();
		final EmbeddedChannel open = new EmbeddedChannel();

		closed.close();
		topology.addNode(at(ASSUMED_PORT + 4).inbound(Optional.of(LedgerStreams.connection(closed))).build());
		topology.addNode(at(ASSUMED_PORT + 5).connection(Optional.of(LedgerStreams.connection(open))).build());
		topology.addNode(at(ASSUMED_PORT + 6).inbound(Optional.of(LedgerStreams.connection(open))).build());

		for (int port = ASSUMED_PORT + 4; port <= ASSUMED_PORT + 6; port++) {
			connectAnew();
			new HelloChannelHandler().typedChannelRead(context, Hello.builder().port(port).build());
		}

		assertThat(inboundAt(ASSUMED_PORT + 4), equalTo(Optional.of(stream)));
		assertThat(inboundAt(ASSUMED_PORT + 5), equalTo(Optional.of(stream)));
		assertThat(inboundAt(ASSUMED_PORT + 6), equalTo(Optional.of(open)));
		IntStream.rangeClosed(ASSUMED_PORT + 4, ASSUMED_PORT + 6).forEach(this::forgetNodeAt);
	}

	/* The topology is shared by all peers and holds a thousand nodes at most, so a stream that names one node for every
	   port it says would fill it alone, and keep the honest peers out until the stream closes */
	@Example
	public void letsAStreamNameOnlyOneNodeAndLeavesRoomForOthers() throws Exception {
		final HelloChannelHandler handler = new HelloChannelHandler();
		final int before = topology.cloneNodes().size();
		int named = 0;
		boolean honestIn = false;

		try {
			for (int n = 0; n < FLOOD; n++) {
				handler.typedChannelRead(context, Hello.builder().port(FLOOD_FIRST_PORT + n).build());
			}

			named = topology.cloneNodes().size() - before;
			honestIn = topology.addNode(at(HONEST_PORT).build());
		} finally {
			IntStream.range(0, FLOOD).forEach(n -> forgetNodeAt(FLOOD_FIRST_PORT + n));
			forgetNodeAt(HONEST_PORT);
		}

		final String measured = "one stream said " + FLOOD + " ports and named " + named + " nodes of "
			+ Topology.MAX_NODES + ", and the honest node was " + (honestIn ? "let in" : "kept out");

		assertThat(measured, named, is(1));
		assertThat(measured, honestIn, is(true));
	}

	/* Anyone can open a connection, say hello and close it, as often as they like. Each of those connections names a
	   node, which leaves as the stream that it said hello on closes: one that stayed until the next round of dialing,
	   a minute or two later, would hold one of the places that the topology has, and a peer that did so a thousand
	   times over would keep out the honest peers that say hello meanwhile. */
	@Example
	public void letsGoOfTheNodeOfAConnectionThatSaidHelloAndClosedAndLeavesRoomForOthers() throws Exception {
		final HelloChannelHandler handler = new HelloChannelHandler();
		final int before = topology.cloneNodes().size();
		int left = 0;
		boolean honestIn = false;

		try {
			for (int n = 0; n < FLOOD; n++) {
				connectAnew();
				handler.typedChannelRead(context, Hello.builder().port(FLOOD_FIRST_PORT + n).build());
				connection.close();
			}

			left = topology.cloneNodes().size() - before;
			honestIn = topology.addNode(at(HONEST_PORT).build());
		} finally {
			IntStream.range(0, FLOOD).forEach(n -> forgetNodeAt(FLOOD_FIRST_PORT + n));
			forgetNodeAt(HONEST_PORT);
		}

		final String measured = FLOOD + " connections said hello and closed, which left " + left + " nodes of "
			+ Topology.MAX_NODES + ", and the honest node was " + (honestIn ? "let in" : "kept out");

		assertThat(measured, left, is(0));
		assertThat(measured, honestIn, is(true));
	}

	private static Node.NodeBuilder at(final int port) {
		return Node.builder().address(new InetSocketAddress(LOCAL, port));
	}

	private Optional<Channel> inboundAt(final int port) {
		return topology.cloneNodes().stream().filter(node -> node.getAddress().getPort() == port).findFirst()
			.flatMap(Node::getInbound).map(Connection::getChannel);
	}

	private boolean forgetNodeAt(final int port) {
		return topology.removeNode(Node.builder().address(new InetSocketAddress(LOCAL, port)).build());
	}
}
