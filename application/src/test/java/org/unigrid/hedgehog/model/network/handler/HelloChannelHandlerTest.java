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
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.Optional;
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
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.network.ChannelMap;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.Hello;
import org.unigrid.hedgehog.server.TestServer;

@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class HelloChannelHandlerTest extends BaseMockedWeldTest {
	private static final String LOCAL = "127.0.0.1";
	private static final int ASSUMED_PORT = 52884;
	private static final int EPHEMERAL_PORT = 40123;

	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;
	@Mocked private QuicStreamChannel stream;
	@Mocked private QuicChannel quic;
	@Mocked private ChannelHandlerContext context;

	@Inject private Topology topology;

	private EmbeddedChannel connection;

	@BeforeTry
	public void connectFromThisHost() {
		TestServer.mockProperties();

		connection = new EmbeddedChannel();
		connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).set(new InetSocketAddress(LOCAL, ASSUMED_PORT));
		connection.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).set(new InetSocketAddress(LOCAL, EPHEMERAL_PORT));

		new Expectations() {{
			context.channel(); result = stream; minTimes = 0;
			stream.parent(); result = quic; minTimes = 0;
			quic.attr((AttributeKey<Object>) any); minTimes = 0;
			result = new Delegate<Attribute<Object>>() {
				Attribute<Object> attr(AttributeKey<Object> key) {
					return connection.attr(key);
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

	@Example
	public void keepsTheFirstHelloOfAConnection() throws Exception {
		final HelloChannelHandler handler = new HelloChannelHandler();

		handler.typedChannelRead(context, Hello.builder().port(ASSUMED_PORT + 1).build());
		handler.typedChannelRead(context, Hello.builder().port(ASSUMED_PORT + 2).build());

		assertThat(RemoteAddress.of(stream), equalTo(Optional.of(new InetSocketAddress(LOCAL, ASSUMED_PORT + 1))));
		forgetNodeAt(ASSUMED_PORT + 1);
		forgetNodeAt(ASSUMED_PORT + 2);
	}

	private boolean forgetNodeAt(final int port) {
		return topology.removeNode(Node.builder().address(new InetSocketAddress(LOCAL, port)).build());
	}
}
