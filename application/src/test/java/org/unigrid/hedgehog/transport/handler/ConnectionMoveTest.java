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

import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicConnectionEvent;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import mockit.Delegate;
import mockit.Expectations;
import mockit.Mocked;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.network.ChannelMap;
import org.unigrid.hedgehog.model.network.ConnectionLimiter;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.server.TestServer;

/* A peer whose connection moves to another address is the same node at that address, whatever its address was named
   by when it came in */
@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class ConnectionMoveTest extends BaseMockedWeldTest {
	private static final String OLD_IP = "10.9.0.1";
	private static final String NEW_IP = "10.9.0.2";
	private static final int EPHEMERAL_PORT = 40123;

	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;
	@Mocked private QuicConnectionEvent event;

	@Inject private Topology topology;

	private final AtomicReference<SocketAddress> movedTo = new AtomicReference<>();
	private EmbeddedChannel connection;

	@BeforeTry
	public void listenForConnectionEvents() {
		TestServer.mockProperties();
		movedTo.set(null);
		connection = new EmbeddedChannel(new ConnectionHandler(new ConnectionLimiter()));

		new Expectations() {{
			event.newAddress(); minTimes = 0;
			result = new Delegate<SocketAddress>() {
				SocketAddress newAddress() {
					return movedTo.get();
				}
			};
		}};
	}

	private void moveTo(final String ip) throws Exception {
		movedTo.set(new InetSocketAddress(ip, EPHEMERAL_PORT));
		connection.pipeline().fireUserEventTriggered(event);
		connection.checkException();
	}

	private static Node at(final String ip, final int port) {
		return Node.builder().address(new InetSocketAddress(ip, port)).build();
	}

	private static void sayHello(final EmbeddedChannel connection, final int port) {
		RemoteAddress.listensOn(new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false), port);
	}

	/* The port that the connection event assumes for every peer is this node's own, while the node that the peer
	   said hello as is named by the port it said, which is the one to look the node up by. Another peer at the old
	   address and the assumed port is not this one, and stays where it is. */
	@Example
	public void shouldMoveTheNodeThatSaidHelloByThePortItSaid() throws Exception {
		moveTo(OLD_IP);

		final int assumed = ((InetSocketAddress) connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).get()).getPort();
		final int said = assumed + 1;

		try {
			sayHello(connection, said);
			assertThat(topology.addNode(at(OLD_IP, said)), is(true));
			assertThat(topology.addNode(at(OLD_IP, assumed)), is(true));

			moveTo(NEW_IP);

			assertThat("the node that said hello is not at the address its connection moved to",
				topology.containsNode(at(NEW_IP, said)), is(true));
			assertThat(topology.containsNode(at(OLD_IP, said)), is(false));
			assertThat("a node of another peer at the old address was moved", topology.containsNode(at(NEW_IP, assumed)),
				is(false));
			assertThat(topology.containsNode(at(OLD_IP, assumed)), is(true));
		} finally {
			Stream.of(at(OLD_IP, said), at(NEW_IP, said), at(OLD_IP, assumed), at(NEW_IP, assumed))
				.forEach(topology::removeNode);
		}
	}

	/* A peer that has said no hello yet is known by the port assumed for it */
	@Example
	public void shouldMoveTheNodeAtTheAssumedPortOfAPeerThatSaidNoHello() throws Exception {
		moveTo(OLD_IP);

		final int assumed = ((InetSocketAddress) connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).get()).getPort();

		try {
			assertThat(topology.addNode(at(OLD_IP, assumed)), is(true));
			moveTo(NEW_IP);

			assertThat(topology.containsNode(at(NEW_IP, assumed)), is(true));
			assertThat(topology.containsNode(at(OLD_IP, assumed)), is(false));
		} finally {
			Stream.of(at(OLD_IP, assumed), at(NEW_IP, assumed)).forEach(topology::removeNode);
		}
	}
}
