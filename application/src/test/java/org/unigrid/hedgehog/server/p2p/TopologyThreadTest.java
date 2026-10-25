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

package org.unigrid.hedgehog.server.p2p;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import mockit.Verifications;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.awaitility.Awaitility.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Dialed;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.handler.RemoteAddress;
import org.unigrid.hedgehog.model.util.Reflection;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.service.ledger.LedgerStreams;

@WeldSetup({ TestServer.class, TopologyThread.class })
public class TopologyThreadTest extends BaseMockedWeldTest {
	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject private Topology topology;
	@Inject private TopologyThread topologyThread;

	@BeforeTry
	public void before() {
		TestServer.mockProperties();
	}

	@Example
	public void shouldRepopulateWithSeedsIfEmpty() {
		/* A seed that refuses the connection is removed at once, which would empty the topology again
		   before it is observed full. */
		new MockUp<TopologyThread.NodeConnectionHandler>() {
			@Mock
			public void accept(Node node) { }
		};

		topology.clear();
		topologyThread.start();
		await().until(() -> topology.cloneNodes().size(), is(Network.getSeeds().length));
	}

	@Example
	public void shouldDropClosedConnection(@Mocked Connection connection, @Mocked Channel channel) {
		final Node node = Node.builder().address(new InetSocketAddress("127.0.100.1", 1000))
			.connection(Optional.of(connection)).build();

		new Expectations() {{
			connection.getChannel(); result = channel;
			channel.isActive(); result = false;
		}};

		topology.addNode(node);
		topology.getChannels().set(channel, node);
		topologyThread.new NodeConnectionHandler(topology, Set.of()).accept(node);

		new Verifications() {{
			connection.closeDirty(); times = 1;
		}};

		assertThat(topology.getChannels().get(channel).isPresent(), is(false));
	}

	@Example
	@SneakyThrows
	public void shouldCloneUniqueList() {
		final Set<Node> original = Reflection.getFieldValue(topology, "nodes");
		final Set<Node> cloned = topology.cloneNodes();

		topology.forEach(n -> {
			assertThat(cloned.contains(n), is(true));
		});

		assertThat(original, not(equalTo(cloned)));
	}

	@Example
	@SneakyThrows
	public void shouldConnectToTheAddressAsGiven() {
		final List<String> hosts = new ArrayList<>();
		final List<Dialed> dialed = new ArrayList<>();

		new MockUp<P2PClient>() {
			@Mock
			public void $init(String hostname, int port, Dialed why) {
				hosts.add(hostname);
				dialed.add(why);
			}
		};

		topologyThread.new NodeConnectionHandler(topology, Set.of()).accept(Node.fromAddress("127.0.0.1:52883"));
		topologyThread.new NodeConnectionHandler(topology, Set.of(Node.fromAddress("127.0.0.1:52884")))
			.accept(Node.fromAddress("127.0.0.1:52884"));
		assertThat(hosts, contains("127.0.0.1", "127.0.0.1"));
		assertThat(dialed, contains(Dialed.CHOSEN, Dialed.CONFIGURED));
	}

	private static Node node(int n, Optional<Connection> connection) {
		return Node.builder().address(new InetSocketAddress("10.2." + n / 256 + "." + n % 256, 1))
			.connection(connection).build();
	}

	/* A connection that this node dialed, as it marks the ones it opens */
	private static Connection dialed() {
		final EmbeddedChannel stream = LedgerStreams.stream(true);

		stream.parent().attr(Dialed.KEY).set(Dialed.CHOSEN);
		return LedgerStreams.connection(stream);
	}

	/* Peers choose what the topology holds, so the node looks after what it dialed, then tries the seeds, and dials no
	   more than it may hold besides them */
	@Example
	public void shouldTurnToTheConnectedThenTheSeedsAndNoMoreThanItMayHold() {
		final List<Node> connected = List.of(node(0, Optional.of(dialed())), node(1, Optional.of(dialed())));
		final Set<Node> seeds = Set.of(node(2, Optional.empty()), node(3, Optional.empty()));
		final Set<Node> nodes = new HashSet<>(connected);

		nodes.addAll(seeds);

		for (int n = 4; n < 200; n++) {
			nodes.add(node(n, Optional.empty()));
		}

		final List<Node> turns = TopologyThread.inTurn(nodes, seeds);

		assertThat(turns, hasSize(TopologyThread.MAX_OUTBOUND + seeds.size()));
		assertThat(turns.subList(0, 2), containsInAnyOrder(connected.toArray()));
		assertThat(turns.subList(2, 4), containsInAnyOrder(seeds.toArray()));
	}

	/* A seed that a peer told of first, as an attacker may to keep it from the operator, is configured all the same once
	   the seeds are resolved, and the connection that was dialed to it is one to a configured node from then on */
	@Example
	public void shouldConfigureASeedThatAPeerToldOfFirst() {
		final EmbeddedChannel stream = LedgerStreams.stream(true);
		final Node told = Node.builder().address(new InetSocketAddress("45.9.9.12", 1234))
			.connection(Optional.of(LedgerStreams.connection(stream))).build();

		stream.parent().attr(Dialed.KEY).set(Dialed.CHOSEN);
		topology.addNode(told);

		new Expectations() {{
			Network.getSeeds(); result = new String[] { "45.9.9.12:1234" };
		}};

		topology.addSeeds();
		assertThat(topology.cloneConfigured(), hasItem(told));
		assertThat(Dialed.of(stream), equalTo(Optional.of(Dialed.CONFIGURED)));
	}

	/* A node that the operator configured stays one when it cannot be dialed for a while, and is dialed again, while one
	   that a peer told of goes */
	@Example
	public void shouldKeepAConfiguredNodeThatCouldNotBeDialed() {
		final Node configured = at("83.0.0.1");
		final Node told = at("84.0.0.1");

		new MockUp<P2PClient>() {
			@Mock
			public void $init(String hostname, int port, Dialed why) {
				throw new IllegalStateException("No answer from " + hostname);
			}
		};

		topology.addConfigured(configured);
		topology.addNode(told);
		topologyThread.new NodeConnectionHandler(topology, Set.of(configured)).accept(configured);
		topologyThread.new NodeConnectionHandler(topology, Set.of(configured)).accept(told);

		assertThat(topology.cloneNodes(), allOf(hasItem(configured), not(hasItem(told))));
		assertThat(topology.cloneConfigured(), hasItem(configured));
	}

	/* A node that connected to this one, and that the operator then configures, is dialed as a configured node in the
	   next round, whose connection is marked so (P2PClient) and pays from a share of its own among the dialed peers */
	@Example
	public void shouldDialAsConfiguredANodeThatHeldOnlyAStreamItOpenedOnceTheOperatorConfiguresIt() {
		final Node said = Node.builder().address(new InetSocketAddress("85.0.0.1", 1234))
			.inbound(Optional.of(helloStream())).build();
		final List<Dialed> dialed = new ArrayList<>();

		new MockUp<P2PClient>() {
			@Mock
			public void $init(String hostname, int port, Dialed why) {
				dialed.add(why);
			}
		};

		topology.addNode(said);
		assertThat(topology.addConfigured(said), is(Topology.Configured.PROMOTED));

		final Set<Node> configured = topology.cloneConfigured();

		TopologyThread.inTurn(topology.cloneNodes(), configured).stream().filter(said::equals)
			.forEach(topologyThread.new NodeConnectionHandler(topology, configured));
		assertThat(dialed, contains(Dialed.CONFIGURED));
	}

	/* A stream that a peer opened to this node and said hello on, which no one dialed */
	private static Connection helloStream() {
		return LedgerStreams.connection(LedgerStreams.stream(true));
	}

	/* A node that said hello is handed to a round only while there is a slot to dial it in, so every round first lets go
	   of the streams that peers opened and that closed, and of the nodes known by such a stream alone: they would hold
	   their places in the topology, and a node that connects again takes its new stream (HelloChannelHandler) */
	@Example
	public void shouldLetGoOfTheClosedStreamsThatPeersOpenedInEveryRound() {
		final Connection closed = helloStream();
		final Node said = Node.builder().address(new InetSocketAddress("46.0.0.1", 1)).inbound(Optional.of(closed))
			.build();
		final Node dialedToo = Node.builder().address(new InetSocketAddress("47.0.0.1", 1))
			.connection(Optional.of(dialed())).inbound(Optional.of(closed)).build();
		final Node configuredToo = Node.builder().address(new InetSocketAddress("48.0.0.1", 1))
			.inbound(Optional.of(closed)).build();
		final Node told = at("49.0.0.1");

		new MockUp<TopologyThread.NodeConnectionHandler>() {
			@Mock
			public void accept(Node node) { }
		};

		closed.close();
		List.of(said, dialedToo, told).forEach(topology::addNode);
		topology.addConfigured(configuredToo);
		topologyThread.start();
		await().until(() -> !topology.containsNode(said));

		assertThat(topology.cloneNodes(), hasItems(dialedToo, configuredToo, told));
		assertThat(topology.cloneNodes().stream().flatMap(node -> node.getInbound().stream()).toList(), empty());
	}

	/* Peers that connect to a node and say hello hold no place among the nodes that it dials: a flood of them from one
	   address leaves it dialing its seeds, the node its operator added, and as many others as it dials, one in each
	   network group, and their number draws out no round of dialing */
	@Example
	public void shouldDialItsSeedsConfiguredNodesAndAsManyOthersAsItDialsWhateverPeersSaidHello() {
		final List<Node> hellos = IntStream.range(0, 1000).mapToObj(port -> Node.builder()
			.address(new InetSocketAddress("45.0.0.1", 1000 + port)).inbound(Optional.of(helloStream())).build())
			.toList();
		final Set<Node> configured = Set.of(at("81.0.0.1"), at("81.1.0.1"), at("82.0.0.1"));
		final Set<Node> nodes = new HashSet<>(hellos);

		nodes.addAll(configured);
		IntStream.range(0, 40).forEach(n -> nodes.add(at("90." + n + ".0.1")));

		final List<Node> turns = TopologyThread.inTurn(nodes, configured);

		assertThat(turns, hasItems(configured.toArray(Node[]::new)));
		assertThat(turns.stream().filter(node -> !configured.contains(node)).count(),
			is((long) TopologyThread.MAX_OUTBOUND));
		assertThat(turns.stream().filter(hellos::contains).count(), lessThanOrEqualTo(1L));
		assertThat(TopologyThread.reconnectionTime(hellos.size()), is(TopologyThread.reconnectionTime(
			TopologyThread.MAX_OUTBOUND)));
	}

	private static Node at(String address) {
		return Node.builder().address(new InetSocketAddress(address, 1)).build();
	}

	/* Anyone can tell a node of as many addresses as they like, so of the nodes that no operator configured it dials one
	   in each network group, which the address dialed decides: an attacker that announces 200 addresses in four /16
	   networks gets four connections, honest nodes in other networks are dialed beside them, and so is a seed in one of
	   the networks of the attacker */
	@Example
	public void shouldDialOneNodeInEachNetworkGroupOfThoseThatNoOperatorConfigured() {
		final List<Node> attacker = IntStream.range(0, 200).mapToObj(n -> at("45." + n % 4 + "." + n / 4 + ".1"))
			.toList();
		final List<Node> honest = IntStream.range(0, 10).mapToObj(n -> at("80." + n + ".0.1")).toList();
		final Node seed = at("45.0.250.1");
		final Set<Node> nodes = new HashSet<>(attacker);

		nodes.addAll(honest);
		nodes.add(seed);

		final List<Node> turns = TopologyThread.inTurn(nodes, Set.of(seed));

		assertThat(turns, hasItem(seed));
		assertThat(turns, hasItems(honest.toArray(Node[]::new)));
		assertThat(turns.stream().filter(attacker::contains).map(node -> RemoteAddress.groupOf(node.getAddress()))
			.toList(), allOf(hasSize(lessThanOrEqualTo(4)), equalTo(turns.stream().filter(attacker::contains)
			.map(node -> RemoteAddress.groupOf(node.getAddress())).distinct().toList())));
	}
}
