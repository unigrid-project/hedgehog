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

package org.unigrid.hedgehog.model.network;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicException;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.Attribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.codec.ChainArbitraries;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.codec.ChainEntryEncoder;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.handler.PingChannelHandler;
import org.unigrid.hedgehog.model.network.initializer.ChainStreamInitializer;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.model.network.schedule.PingSchedule;
import org.unigrid.hedgehog.server.BaseServerTest;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.service.chain.ChainHost;

public class ChainStreamTest extends BaseServerTest {
	private static final int WAIT_SECONDS = 5;
	private static final int STALL_MILLIS = 500;
	private static final int PACKETS = 400;
	private static final int SERVERS = 2;
	private static final int TEST_SERVERS = 20;
	private static final long THIRD_CLIENT_STREAM = 8;
	private static final String OPEN_FAILED = "Could not open the chain stream: ";
	private static final String STALL = "stall";
	private static final ChainEntry PACKET = ChainEntry.builder().entries(ChainArbitraries.entries().list()
		.ofSize(ChainCodecs.MAX_ENTRIES_PER_PACKET).sample()).build();
	private static final Consumer<QuicChannel> UNTOUCHED = connection -> { };

	private static final List<Channel> arrivals = new CopyOnWriteArrayList<>();
	private static final List<Channel> chainPingRequests = new CopyOnWriteArrayList<>();
	private static final List<Channel> chainPingAnswers = new CopyOnWriteArrayList<>();
	private static final List<Node> peers = new CopyOnWriteArrayList<>();
	private static final Map<Long, Channel> pingedAtTheServer = new ConcurrentHashMap<>();
	private static final AtomicReference<Consumer<QuicChannel>> afterTheMainStream = new AtomicReference<>(UNTOUCHED);
	private static volatile boolean stalling;

	/* Two servers, never none: the servers of the base class may be an empty list, which a property with a single
	   try would pass without testing anything */
	@Provide
	public Arbitrary<List<TestServer>> twoTestServers(@ForAll @IntRange(max = TEST_SERVERS - SERVERS - 1) int from) {
		return provideTestServers(from, SERVERS);
	}

	/* Notes the main stream of a server that each ping arrives on, by the time the ping carries, as a server may get
	   pings on other connections too, and the chain streams that get a ping, which carry no type of their own.
	   Notes the stream every chain packet arrives on and stops reading it when asked to, and notes the nodes the
	   topology learns from a hello. Once the main stream of the next client is set up, before the client opens its
	   chain stream, a test may do something to the connection of that client. The mock-ups are set up once for all
	   properties, as one set up before a property only holds for the first, and keep their state in static fields. */
	@BeforeContainer
	private static void recordWhereTheTrafficArrives() {
		new MockUp<PingChannelHandler>() {
			@Mock public void typedChannelRead(Invocation invocation, ChannelHandlerContext ctx, Ping ping) {
				if (RegisterQuicChannelInitializer.Type.SERVER.is(ctx.channel())) {
					pingedAtTheServer.put(ping.getNanoTime(), ctx.channel());
				} else if (ctx.channel().attr(RegisterQuicChannelInitializer.CHANNEL_TYPE_KEY).get() == null) {
					(ping.isResponse() ? chainPingAnswers : chainPingRequests).add(ctx.channel());
				}

				invocation.proceed(ctx, ping);
			}
		};

		new MockUp<ChainHost>() {
			@Mock public List<EntryRefused> receive(Channel peer, ChainEntry entries) {
				arrivals.add(peer);

				if (stalling && peer.pipeline().get(STALL) == null) {
					stall(peer);
				}

				return List.of();
			}
		};

		new MockUp<Topology>() {
			@Mock public boolean addNode(Invocation invocation, Node node) {
				if (node.getConnection().isPresent()) {
					peers.add(node);
				}

				return invocation.proceed(node);
			}
		};

		new MockUp<RegisterQuicChannelInitializer>() {
			@Mock void initChannel(Invocation invocation, QuicStreamChannel channel) {
				invocation.proceed(channel);

				if (RegisterQuicChannelInitializer.Type.CLIENT.is(channel)) {
					afterTheMainStream.getAndSet(UNTOUCHED).accept(channel.parent());
				}
			}
		};
	}

	/* Stops reading the stream. A decoder that has handed on what it read without decoding anything itself asks for
	   more even while the stream does not read on its own, so those requests go no further. */
	private static void stall(final Channel stream) {
		stream.config().setAutoRead(false);
		stream.pipeline().addFirst(STALL, new ChannelOutboundHandlerAdapter() {
			@Override public void read(final ChannelHandlerContext ctx) { }
		});
	}

	@BeforeProperty
	private void forgetEarlierTraffic() {
		arrivals.clear();
		chainPingRequests.clear();
		chainPingAnswers.clear();
		peers.clear();
		stalling = false;
		afterTheMainStream.set(UNTOUCHED);
	}

	@SneakyThrows
	private static P2PClient connectTo(final TestServer server) {
		return new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());
	}

	/* Connects while noting what the client warns about, read as ChainOutboxTest reads the outbox */
	private static P2PClient connectNoting(final TestServer server, final List<ILoggingEvent> warnings) {
		ChainStreamTest.class.getModule().addReads(ModuleLayer.boot().findModule("ch.qos.logback.core")
			.orElseThrow());

		final Logger logger = (Logger) LoggerFactory.getLogger(P2PClient.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();
		logger.addAppender(appender);

		try {
			return connectTo(server);
		} finally {
			logger.detachAppender(appender);
			warnings.addAll(appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
				.toList());
		}
	}

	private static ByteBuf encoded(final ChannelHandler encoder, final Packet packet) {
		final EmbeddedChannel channel = new EmbeddedChannel(encoder);

		channel.writeOutbound(packet);
		return channel.readOutbound();
	}

	private static QuicChannel connectionOf(final P2PClient client) {
		return client.getChannel().parent();
	}

	/* The stream of a server the ping, or the answer to it, arrived on */
	private static Channel awaitAtTheServer(final Ping ping) {
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> pingedAtTheServer.containsKey(ping.getNanoTime()));
		return pingedAtTheServer.get(ping.getNanoTime());
	}

	/* The end of the client's main stream at the server, found by a ping on it */
	private static Channel serverEndOfTheMainStream(final P2PClient client) {
		final Ping ping = Ping.builder().build();

		client.send(ping);
		return awaitAtTheServer(ping);
	}

	/* The chain stream the QUIC connection of a stream remembers, once there is one */
	private static QuicStreamChannel awaitTheChainStreamBeside(final Channel stream) {
		final Attribute<QuicStreamChannel> remembered = stream.parent().attr(ChainStreamInitializer.CHAIN_STREAM_KEY);

		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> remembered.get() != null);
		return remembered.get();
	}

	/* The streams of one QUIC connection that chain packets arrived on. A connection of an earlier test hands what
	   its chain stream still holds to the chain host as it closes, so arrivals are told apart by connection. */
	private static List<Channel> arrivalsOn(final Channel connection) {
		return arrivals.stream().filter(stream -> stream.parent() == connection).toList();
	}

	private static Channel awaitAnArrivalOn(final Channel connection) {
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> !arrivalsOn(connection).isEmpty());
		return arrivalsOn(connection).get(0);
	}

	/* The listeners on the close of a stream run on its event loop, so a task queued behind the close sees them done */
	@SneakyThrows
	private static void closeAndSettle(final Channel stream) {
		stream.close().sync();
		stream.eventLoop().submit(() -> { }).sync();
	}

	/* Takes every bidirectional stream the server allows but the main one, so QUIC refuses the next stream */
	private static void useUpTheStreams(final QuicChannel connection) {
		for (int stream = 1; stream < Network.MAX_STREAMS; stream++) {
			connection.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter());
		}
	}

	/* The server gets a chain packet on the second stream of the connection, which the connection remembers and
	   which the node of the topology resolves to */
	private static void assertChainPacketsUseTheirOwnStream(P2PClient client, Channel atTheServer) {
		final QuicStreamChannel chain = (QuicStreamChannel) client.getChainChannel().orElseThrow();

		assertThat(chain.parent(), is(sameInstance(client.getChannel().parent())));
		assertThat(chain.streamId(), is(Network.CHAIN_STREAM_ID));
		assertThat(client.getChannel().streamId(), is(not(Network.CHAIN_STREAM_ID)));

		chain.writeAndFlush(PACKET);

		final QuicStreamChannel arrived = (QuicStreamChannel) awaitAnArrivalOn(atTheServer);

		assertThat(arrived.streamId(), is(Network.CHAIN_STREAM_ID));
		assertThat(arrived.parent().attr(ChainStreamInitializer.CHAIN_STREAM_KEY).get(), is(sameInstance(arrived)));
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> peers.stream().anyMatch(node ->
			node.getConnection().orElseThrow().getChainChannel().equals(Optional.of(arrived))));
	}

	/* The server stops reading the chain stream after the first packet, so the rest piles up behind it. A ping
	   on the main stream still gets through, and no packet of the chain stream follows it. */
	private static void assertAPingPassesAStalledChainStream(P2PClient client, Channel atTheServer) {
		arrivals.clear();
		stalling = true;

		for (int i = 0; i < PACKETS; i++) {
			client.getChainChannel().orElseThrow().writeAndFlush(PACKET);
		}

		awaitAnArrivalOn(atTheServer);
		assertThat(serverEndOfTheMainStream(client).parent(), is(sameInstance(atTheServer)));

		final int delivered = arrivalsOn(atTheServer).size();

		await().during(STALL_MILLIS, TimeUnit.MILLISECONDS).atMost(WAIT_SECONDS, TimeUnit.SECONDS)
			.until(() -> arrivalsOn(atTheServer).size() == delivered);
		assertThat(delivered < PACKETS, is(true));
	}

	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void keepsChainTrafficOnAStreamOfItsOwn(@ForAll("twoTestServers") List<TestServer> servers,
		@Mocked PingSchedule pingSchedule) {

		for (TestServer server : servers) {
			final P2PClient client = connectTo(server);

			try {
				final Channel atTheServer = serverEndOfTheMainStream(client).parent();

				stalling = false;
				assertChainPacketsUseTheirOwnStream(client, atTheServer);
				assertAPingPassesAStalledChainStream(client, atTheServer);
			} finally {
				stalling = false;
				client.closeDirty();
			}
		}
	}

	/* QUIC creates a stream at the peer with its first bytes, so without the ping that opens it the server would
	   have no end of the chain stream to write on until the client sent a chain packet. The server only answers it. */
	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void opensTheChainStreamWithAPingSoTheServerCanWriteOnIt(@ForAll("twoTestServers") List<TestServer> servers,
		@Mocked PingSchedule pingSchedule) {

		for (TestServer server : servers) {
			final P2PClient client = connectTo(server);

			try {
				final Channel main = serverEndOfTheMainStream(client);
				final QuicStreamChannel chain = awaitTheChainStreamBeside(main);

				assertThat(chain.streamId(), is(Network.CHAIN_STREAM_ID));
				assertThat(chain.isLocalCreated(), is(false));
				assertThat(arrivalsOn(main.parent()), empty());
				await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> chainPingRequests.contains(chain)
					&& chainPingAnswers.contains(client.getChainChannel().orElseThrow()));
				assertThat(chainPingRequests, not(hasItem(client.getChainChannel().orElseThrow())));
				assertThat(peers.stream().filter(node -> node.getConnection().orElseThrow().getChannel() == main)
					.map(node -> node.getConnection().orElseThrow().getChainChannel()).toList(),
					contains(Optional.of(chain)));

				chain.writeAndFlush(PACKET);
				assertThat(awaitAnArrivalOn(connectionOf(client)), is(client.getChainChannel().orElseThrow()));
			} finally {
				client.closeDirty();
			}
		}
	}

	/* The main stream and every further stream a client opens have no chain stages, at either end: a chain packet
	   on them is not acted on, which the answer to a ping behind it shows, as the bytes of a stream are read in
	   order. The same bytes on the chain stream are. */
	@SneakyThrows
	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void leavesChainPacketsOnEveryOtherStreamAlone(@ForAll("twoTestServers") List<TestServer> servers,
		@Mocked PingSchedule pingSchedule) {

		final ByteBuf entry = encoded(new ChainEntryEncoder(), PACKET);

		for (TestServer server : servers) {
			final P2PClient client = connectTo(server);

			try {
				client.getChannel().write(entry.retainedDuplicate());

				final Channel main = serverEndOfTheMainStream(client);
				final Ping answered = Ping.builder().build();

				main.write(entry.retainedDuplicate());
				main.writeAndFlush(answered);
				assertThat(awaitAtTheServer(answered), is(sameInstance(main)));

				final QuicStreamChannel third = connectionOf(client).createStream(QuicStreamType.BIDIRECTIONAL,
					new ChannelInboundHandlerAdapter()).sync().getNow();
				final Ping behind = Ping.builder().build();

				assertThat(third.streamId(), is(THIRD_CLIENT_STREAM));
				third.write(entry.retainedDuplicate());
				third.writeAndFlush(encoded(new PingEncoder(), behind));
				assertThat(((QuicStreamChannel) awaitAtTheServer(behind)).streamId(), is(THIRD_CLIENT_STREAM));
				assertThat(arrivalsOn(main.parent()), empty());
				assertThat(arrivalsOn(connectionOf(client)), empty());

				client.getChainChannel().orElseThrow().writeAndFlush(entry.retainedDuplicate());
				assertThat(awaitAnArrivalOn(main.parent()), is(awaitTheChainStreamBeside(main)));
			} finally {
				client.closeDirty();
			}
		}

		entry.release();
	}

	/* A frame header that claims more than the chain stream takes closes that stream at the server and leaves the
	   connection, its main stream and the topology node of the peer as they were */
	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void closesOnlyTheChainStreamOnAFrameOverItsLimit(@ForAll("twoTestServers") List<TestServer> servers,
		@Mocked PingSchedule pingSchedule) {

		for (TestServer server : servers) {
			final P2PClient client = connectTo(server);

			try {
				final Channel main = serverEndOfTheMainStream(client);
				final QuicStreamChannel chain = awaitTheChainStreamBeside(main);

				client.getChainChannel().orElseThrow().writeAndFlush(Unpooled.buffer()
					.writeShort(FrameDecoder.MAGIC).writeShort(Packet.Type.CHAIN_ENTRY.getValue())
					.writeInt(ChainCodecs.MAX_FRAME_BYTES - ChainCodecs.FRAME_HEADER_BYTES + 1));

				await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> !chain.isActive());
				assertThat(main.parent().attr(ChainStreamInitializer.CHAIN_STREAM_KEY).get(), is(nullValue()));
				assertThat(ConnectionContainer.builder().channel((QuicStreamChannel) main).build().getChainChannel(),
					equalTo(Optional.empty()));
				assertThat(main.parent().isActive(), is(true));
				assertThat(main.isActive(), is(true));
				assertThat(serverEndOfTheMainStream(client), is(sameInstance(main)));
			} finally {
				client.closeDirty();
			}
		}
	}

	/* A stream that closes after a newer one took its place leaves the newer one remembered, and a closed stream
	   that is still remembered, as it is until the listener on its close has run, is not handed out */
	@SneakyThrows
	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void remembersTheNewestChainStreamOfAConnection(@ForAll("twoTestServers") List<TestServer> servers,
		@Mocked PingSchedule pingSchedule) {

		final P2PClient client = connectTo(servers.get(0));

		try {
			final Attribute<QuicStreamChannel> remembered = connectionOf(client)
				.attr(ChainStreamInitializer.CHAIN_STREAM_KEY);
			final QuicStreamChannel first = (QuicStreamChannel) client.getChainChannel().orElseThrow();
			final QuicStreamChannel newer = connectionOf(client).createStream(QuicStreamType.BIDIRECTIONAL,
				new ChainStreamInitializer(RegisterQuicChannelInitializer.Type.CLIENT)).sync().getNow();

			assertThat(remembered.get(), is(sameInstance(newer)));
			closeAndSettle(first);
			assertThat(remembered.get(), is(sameInstance(newer)));
			assertThat(client.getChainChannel(), equalTo(Optional.of(newer)));

			remembered.set(first);
			assertThat(client.getChainChannel(), equalTo(Optional.empty()));
			remembered.set(newer);

			closeAndSettle(newer);
			assertThat(remembered.get(), is(nullValue()));
			assertThat(client.getChainChannel(), equalTo(Optional.empty()));
		} finally {
			client.closeDirty();
		}
	}

	/* The main stream carries everything but chain traffic, so a client whose peer allows it no chain stream keeps
	   the connection, and says so in the log by the kind of failure alone. QUIC reports that failure, like the one
	   on a connection that is gone, as a checked exception. */
	@Property(tries = 1, shrinking = ShrinkingMode.OFF)
	public void keepsTheConnectionWhenTheChainStreamCannotBeOpened(@ForAll("twoTestServers") List<TestServer> servers,
		@Mocked PingSchedule pingSchedule) {

		for (TestServer server : servers) {
			final List<ILoggingEvent> warnings = new ArrayList<>();

			afterTheMainStream.set(ChainStreamTest::useUpTheStreams);

			final P2PClient client = connectNoting(server, warnings);

			try {
				assertThat(warnings.stream().map(ILoggingEvent::getFormattedMessage).toList(),
					contains(OPEN_FAILED + QuicException.class.getName()));
				assertThat(warnings.get(0).getThrowableProxy(), is(nullValue()));
				assertThat(client.getChainChannel(), equalTo(Optional.empty()));
				assertThat(connectionOf(client).attr(ChainStreamInitializer.CHAIN_STREAM_KEY).get(), is(nullValue()));

				final Channel main = serverEndOfTheMainStream(client);

				assertThat(main.parent().attr(ChainStreamInitializer.CHAIN_STREAM_KEY).get(), is(nullValue()));
				assertThat(connectionOf(client).isActive(), is(true));
				assertThat(client.getChannel().isActive(), is(true));
			} finally {
				client.closeDirty();
			}
		}
	}
}
