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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import jakarta.enterprise.inject.spi.CDI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeProperty;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.ConnectionContainer;
import org.unigrid.hedgehog.model.network.Node;
import static org.unigrid.hedgehog.model.network.QuicLoopback.PREVIOUS_RELEASE;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.handler.PingChannelHandler;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.network.initializer.ChainStreamInitializer;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.initializer.StreamRouter;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.network.schedule.PingSchedule;
import org.unigrid.hedgehog.model.spork.GridSpork;
import static org.unigrid.hedgehog.model.spork.GridSpork.Type.COMMUNICATION;
import static org.unigrid.hedgehog.model.spork.GridSpork.Type.MINT_STORAGE;
import static org.unigrid.hedgehog.model.spork.GridSpork.Type.MINT_SUPPLY;
import static org.unigrid.hedgehog.model.spork.GridSpork.Type.STORAGE;
import static org.unigrid.hedgehog.model.spork.GridSpork.Type.VESTING_STORAGE;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.BaseServerTest;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.service.chain.NettyChainTransport;

/* A node of the previous release offers only the older protocols, and reads neither a communication spork nor chain
   traffic. Its server is stood up here with the pipeline of this release offering only those protocols, which is
   how a node of this release has to behave towards it, and its client is a client of this release offering them. */
public class PreviousReleasePeerTest extends BaseServerTest {
	private static final String AGREED_WITH_THE_PREVIOUS_RELEASE = PREVIOUS_RELEASE[0];
	private static final List<GridSpork.Type> EVERY_SPORK = List.of(MINT_STORAGE, MINT_SUPPLY, VESTING_STORAGE,
		STORAGE, COMMUNICATION);
	private static final List<GridSpork.Type> READ_BY_THE_PREVIOUS_RELEASE = List.of(MINT_STORAGE, MINT_SUPPLY,
		VESTING_STORAGE, STORAGE);
	private static final Instant PUBLISHED_AT = Instant.parse("2025-06-01T12:00:00Z");
	private static final String GRIDNODE = "gridnode";
	private static final String GRIDNODE_ADDRESS = "127.0.0.1:1";
	private static final int WAIT_SECONDS = 5;
	private static final int QUIET_MILLIS = 500;
	private static final int TEST_SERVERS = 20;

	private record Arrival(Channel stream, GridSpork.Type type) { }

	private static final List<Arrival> sporks = new CopyOnWriteArrayList<>();
	private static final Map<Long, Channel> pings = new ConcurrentHashMap<>();
	private static final Map<Long, Channel> answers = new ConcurrentHashMap<>();
	private static final List<QuicStreamChannel> routed = new CopyOnWriteArrayList<>();

	@Provide
	public Arbitrary<TestServer> aTestServer(@ForAll @IntRange(max = TEST_SERVERS - 2) int from) {
		return provideTestServers(from, 1).map(servers -> servers.get(0));
	}

	/* Notes the stream each spork and each ping arrives on, the pings by the time they carry, which their answers
	   carry too, and every stream a server sets up. The mock-ups are set up once for all properties, as one set up
	   before a property only holds for the first, and keep their state in static fields. */
	@BeforeContainer
	private static void recordWhatArrives() {
		new MockUp<PublishSporkChannelHandler>() {
			@Mock public void typedChannelRead(Invocation invocation, ChannelHandlerContext ctx,
				PublishSpork spork) {

				if (ctx != null) {
					sporks.add(new Arrival(ctx.channel(), spork.getGridSpork().getType()));
				}

				invocation.proceed(ctx, spork);
			}
		};

		new MockUp<PingChannelHandler>() {
			@Mock public void typedChannelRead(Invocation invocation, ChannelHandlerContext ctx, Ping ping) {
				(ping.isResponse() ? answers : pings).put(ping.getNanoTime(), ctx.channel());
				invocation.proceed(ctx, ping);
			}
		};

		new MockUp<StreamRouter>() {
			@Mock void initChannel(Invocation invocation, QuicStreamChannel stream) {
				routed.add(stream);
				invocation.proceed(stream);
			}
		};
	}

	@BeforeProperty
	private void forgetEarlierTraffic() {
		sporks.clear();
		pings.clear();
		answers.clear();
		routed.clear();
	}

	private static GridSpork spork(GridSpork.Type type, Instant at) {
		final GridSpork spork = GridSpork.create(type);

		spork.setTimeStamp(at);
		spork.setPreviousTimeStamp(at);
		spork.setPreviousData(spork.getData());
		spork.setSignature(new byte[0]);
		return spork;
	}

	/* Into the database every node of this test reads, as they all resolve the same one */
	private static void storeOneSporkOfEveryType() {
		CDIUtil.resolveAndRun(SporkDatabase.class,
			db -> EVERY_SPORK.forEach(type -> db.set(spork(type, PUBLISHED_AT))));
	}

	@SneakyThrows
	private static PublishSpork signedBy(Signature signer, GridSpork.Type type) {
		final GridSpork spork = spork(type, PUBLISHED_AT.plusSeconds(1));

		spork.sign(signer.getPrivateKey());
		return PublishSpork.builder().gridSpork(spork).build();
	}

	@SneakyThrows
	private static Channel standUpAServerOfThePreviousRelease(EventLoopGroup group) {
		return new Bootstrap().group(group).channel(NioDatagramChannel.class)
			.handler(P2PServer.codec(InsecureQuicTokenHandler.INSTANCE, PREVIOUS_RELEASE))
			.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
	}

	private static Channel awaitPing(Ping ping) {
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> pings.containsKey(ping.getNanoTime()));
		return pings.get(ping.getNanoTime());
	}

	/* The server end of the main stream of the client, once each end has read what the other sent on opening it:
	   the ping follows the sporks of the client and its answer those of the server */
	private static Channel exchange(P2PClient client) {
		final Ping ping = Ping.builder().build();

		client.send(ping);
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> answers.containsKey(ping.getNanoTime()));
		return pings.get(ping.getNanoTime());
	}

	private static List<GridSpork.Type> sporksOn(Channel stream) {
		return sporks.stream().filter(arrival -> arrival.stream() == stream).map(Arrival::type).toList();
	}

	private static String protocolOf(Channel stream) {
		return ((QuicChannel) stream.parent()).sslEngine().getApplicationProtocol();
	}

	private static List<QuicStreamChannel> routedBy(Channel server) {
		return routed.stream().filter(stream -> stream.parent().parent() == server).toList();
	}

	@SneakyThrows
	private static Node nodeAt(String address, Channel stream) {
		final Node node = Node.fromAddress(address);

		node.setConnection(Optional.of(ConnectionContainer.builder().channel((QuicStreamChannel) stream).build()));
		return node;
	}

	/* The stream the chain host would use towards a gridnode at the other end of the stream */
	private static Optional<Channel> chainStreamBeside(Channel stream) {
		final Node node = nodeAt(GRIDNODE_ADDRESS, stream);
		final Gridnode gridnode = Gridnode.builder().id(GRIDNODE).hostName(GRIDNODE_ADDRESS).build();

		return new NettyChainTransport(() -> Set.of(node), () -> List.of(gridnode)).streamTo(GRIDNODE);
	}

	private static ByteBuf encoded(Ping ping) {
		final EmbeddedChannel channel = new EmbeddedChannel(new PingEncoder());

		channel.writeOutbound(ping);
		return channel.readOutbound();
	}

	/* What a client sends on a further stream reaches the main pipeline of the server, also on the stream that
	   carries chain traffic between two nodes of this release */
	@SneakyThrows
	private static void assertTheSecondStreamIsAMainStream(P2PClient client, Channel atTheServer) {
		final QuicStreamChannel second = ((QuicChannel) client.getChannel().parent())
			.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter()).sync().getNow();
		final Ping ping = Ping.builder().build();

		assertThat(second.streamId(), is(Network.CHAIN_STREAM_ID));
		second.writeAndFlush(encoded(ping));

		final Channel arrived = awaitPing(ping);

		assertThat(((QuicStreamChannel) arrived).streamId(), is(Network.CHAIN_STREAM_ID));
		assertThat(arrived.parent(), is(sameInstance(atTheServer.parent())));
		assertThat(RegisterQuicChannelInitializer.Type.SERVER.is(arrived), is(true));
		assertThat(atTheServer.parent().attr(ChainStreamInitializer.CHAIN_STREAM_KEY).get(), is(nullValue()));
	}

	/* The ping from the server reaches the client behind every spork the server sent before it */
	private static void awaitWhatTheServerSentBefore(Channel atTheServer) {
		final Ping ping = Ping.builder().build();

		atTheServer.writeAndFlush(ping);
		awaitPing(ping);
	}

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void talksToAServerOfThePreviousReleaseAsThatReleaseDid(@Mocked PingSchedule pingSchedule)
		throws Exception {

		final EventLoopGroup group = new NioEventLoopGroup(1);
		final Channel server = standUpAServerOfThePreviousRelease(group);
		final InetSocketAddress address = (InetSocketAddress) server.localAddress();

		storeOneSporkOfEveryType();

		final P2PClient client = new P2PClient(address.getHostString(), address.getPort());

		try {
			final Channel atTheServer = exchange(client);

			assertThat(protocolOf(client.getChannel()), is(AGREED_WITH_THE_PREVIOUS_RELEASE));
			assertThat(protocolOf(atTheServer), is(AGREED_WITH_THE_PREVIOUS_RELEASE));
			assertThat(sporksOn(atTheServer), equalTo(READ_BY_THE_PREVIOUS_RELEASE));
			assertThat(sporksOn(client.getChannel()), equalTo(READ_BY_THE_PREVIOUS_RELEASE));
			assertThat(client.getChainChannel(), is(Optional.empty()));
			await().during(QUIET_MILLIS, TimeUnit.MILLISECONDS).atMost(WAIT_SECONDS, TimeUnit.SECONDS)
				.until(() -> routedBy(server).size() == 1);
			assertThat(routedBy(server), contains(sameInstance(atTheServer)));
			assertThat(chainStreamBeside(atTheServer), is(Optional.empty()));
		} finally {
			client.closeDirty();
			server.close().sync();
			group.shutdownGracefully();
		}
	}

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void takesAClientOfThePreviousReleaseAsThatReleaseDid(@ForAll("aTestServer") TestServer server,
		@Mocked PingSchedule pingSchedule) throws Exception {

		storeOneSporkOfEveryType();

		final P2PClient client = new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort(),
			PREVIOUS_RELEASE);

		try {
			final Channel atTheServer = exchange(client);

			assertThat(protocolOf(client.getChannel()), is(AGREED_WITH_THE_PREVIOUS_RELEASE));
			assertThat(protocolOf(atTheServer), is(AGREED_WITH_THE_PREVIOUS_RELEASE));
			assertThat(sporksOn(atTheServer), equalTo(READ_BY_THE_PREVIOUS_RELEASE));
			assertThat(sporksOn(client.getChannel()), equalTo(READ_BY_THE_PREVIOUS_RELEASE));
			assertThat(client.getChainChannel(), is(Optional.empty()));
			assertTheSecondStreamIsAMainStream(client, atTheServer);
			assertThat(chainStreamBeside(atTheServer), is(Optional.empty()));
		} finally {
			client.closeDirty();
		}
	}

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void agreesOnTheProxyProtocolWithANodeOfThisRelease(@ForAll("aTestServer") TestServer server,
		@Mocked PingSchedule pingSchedule) throws Exception {

		storeOneSporkOfEveryType();

		final P2PClient client = new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());

		try {
			final Channel atTheServer = exchange(client);

			assertThat(protocolOf(client.getChannel()), is(Network.PROXY_PROTOCOL));
			assertThat(protocolOf(atTheServer), is(Network.PROXY_PROTOCOL));
			assertThat(sporksOn(atTheServer), equalTo(EVERY_SPORK));
			assertThat(sporksOn(client.getChannel()), equalTo(EVERY_SPORK));
			assertThat(client.getChainChannel().map(stream -> ((QuicStreamChannel) stream).streamId()),
				is(Optional.of(Network.CHAIN_STREAM_ID)));
			await().atMost(WAIT_SECONDS, TimeUnit.SECONDS)
				.until(() -> chainStreamBeside(atTheServer).isPresent());
			assertThat(chainStreamBeside(atTheServer).orElseThrow(),
				is(sameInstance(atTheServer.parent().attr(ChainStreamInitializer.CHAIN_STREAM_KEY).get())));
		} finally {
			client.closeDirty();
		}
	}

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void relaysACommunicationSporkToNodesOfThisReleaseOnly(@ForAll("aTestServer") TestServer server,
		@Mocked PingSchedule pingSchedule) throws Exception {

		final Signature signer = new Signature();

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { signer.getPublicKey() };
			}
		};

		storeOneSporkOfEveryType();

		final String host = server.getP2p().getHostName();
		final P2PClient current = new P2PClient(host, server.getP2p().getPort());
		final P2PClient previous = new P2PClient(host, server.getP2p().getPort(), PREVIOUS_RELEASE);
		final Topology topology = CDI.current().select(Topology.class).get();
		final Node toCurrent = nodeAt("127.0.0.2:1", exchange(current));
		final Node toPrevious = nodeAt("127.0.0.3:1", exchange(previous));

		try {
			topology.addNode(toCurrent);
			topology.addNode(toPrevious);
			sporks.clear();

			new PublishSporkChannelHandler().typedChannelRead(null, signedBy(signer, COMMUNICATION));
			new PublishSporkChannelHandler().typedChannelRead(null, signedBy(signer, MINT_SUPPLY));
			awaitWhatTheServerSentBefore(toCurrent.getConnection().orElseThrow().getChannel());
			awaitWhatTheServerSentBefore(toPrevious.getConnection().orElseThrow().getChannel());

			assertThat(sporksOn(current.getChannel()), hasItems(COMMUNICATION, MINT_SUPPLY));
			assertThat(sporksOn(previous.getChannel()), hasItem(MINT_SUPPLY));
			assertThat(sporksOn(previous.getChannel()), not(hasItem(COMMUNICATION)));
		} finally {
			topology.removeNode(toCurrent);
			topology.removeNode(toPrevious);
			current.closeDirty();
			previous.closeDirty();
		}
	}
}
