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

package org.unigrid.hedgehog.service.ledger;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.remoteAt;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.BlockRequest;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Status;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockBatch;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockRequest;
import org.unigrid.hedgehog.model.network.packet.LedgerConsensus;
import org.unigrid.hedgehog.model.network.packet.LedgerHeartbeat;
import org.unigrid.hedgehog.model.network.packet.LedgerStatus;
import org.unigrid.hedgehog.model.network.packet.LedgerTransaction;

public class NettyLedgerNetworkTest {
	private static final String FIRST = "10.0.0.1";
	private static final String SECOND = "10.0.0.2";
	private static final int PORT = 52883;

	private static Supplier<Optional<InetSocketAddress>> remote(final String address) {
		return remoteAt(address, PORT);
	}

	private static Node nodeWith(final Channel channel, final String address) {
		return nodeWith(channel, address, PORT);
	}

	@SneakyThrows
	private static Node nodeWith(final Channel channel, final String address, final int port) {
		final Node node = Node.fromAddress(address + ":" + port);

		node.setConnection(Optional.of(connectionTo(channel)));
		return node;
	}

	private static Connection connectionTo(final Channel channel) {
		return new Connection() {
			@Override public Channel getChannel() {
				return channel;
			}

			@Override public ChannelFuture send(Packet packet) {
				return channel.writeAndFlush(packet);
			}

			@Override public void close() {
				channel.close();
			}

			@Override public void closeDirty() {
				channel.close();
			}
		};
	}

	/* A node that counts how often its address is read */
	private static final class WatchedNode extends Node {
		private int reads;

		private WatchedNode(final String address, final Optional<Connection> connection) {
			setAddress(new InetSocketAddress(address, PORT));
			setConnection(connection);
		}

		@Override
		public InetSocketAddress getAddress() {
			reads++;
			return super.getAddress();
		}
	}

	private static boolean connectionOf(final Node node, final Channel channel) {
		return node.getConnection().map(Connection::getChannel).filter(held -> held == channel).isPresent();
	}

	private static NettyLedgerNetwork networkOf(final PendingRequests pending, final Predicate<Channel> outbound,
		final Predicate<Channel> negotiated, final Set<Node> nodes) {

		return new NettyLedgerNetwork(() -> nodes, pending, outbound, negotiated);
	}

	/* Every stream has settled on the ledger protocol unless a test says that it did not */
	private static NettyLedgerNetwork networkOf(final PendingRequests pending, final Predicate<Channel> outbound,
		final Set<Node> nodes) {

		return networkOf(pending, outbound, channel -> true, nodes);
	}

	/* The channels of the topology are those this node opened, as they are for the dialled nodes of a real one */
	private static NettyLedgerNetwork networkOf(final PendingRequests pending, final Node... nodes) {
		return networkOf(pending, channel -> Stream.of(nodes).anyMatch(node -> connectionOf(node, channel)),
			Set.of(nodes));
	}

	private static NettyLedgerNetwork networkOf(final Node... nodes) {
		return networkOf(new PendingRequests(), nodes);
	}

	private static LedgerPeer onlyPeerOf(final NettyLedgerNetwork network) {
		final List<LedgerPeer> peers = network.peers();

		assertThat(peers, hasSize(1));
		return peers.get(0);
	}

	/* Which channel a peer writes to is told by where its object turns up */
	private static boolean writesTo(final LedgerPeer peer, final EmbeddedChannel channel) {
		final Status marker = LedgerWireFixtures.status(0);

		peer.send(marker);
		return channel.readOutbound() != null;
	}

	@Property(tries = 20)
	public void sendsEachKindOfWireObjectAsItsPacket(@ForAll @IntRange(min = 0, max = 100) int seed) {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final Transaction transaction = LedgerWireFixtures.transaction(seed);
		final Message message = LedgerWireFixtures.message(seed);
		final Heartbeat heartbeat = LedgerWireFixtures.heartbeat(seed);
		final Status status = LedgerWireFixtures.status(seed);
		final LedgerPeer peer = networkOf().peerOf(channel, remote(FIRST));

		peer.send(transaction);
		peer.send(message);
		peer.send(heartbeat);
		peer.send(status);

		assertThat(channel.outboundMessages(), contains(LedgerTransaction.builder().transaction(transaction).build(),
			LedgerConsensus.builder().message(message).build(),
			LedgerHeartbeat.builder().heartbeat(heartbeat).build(),
			LedgerStatus.builder().status(status).build()));
	}

	@Example
	public void refusesWhatIsNotALedgerObject() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final LedgerPeer peer = networkOf().peerOf(channel, remote(FIRST));

		assertThrows(IllegalArgumentException.class, () -> peer.send("not a ledger object"));
		assertThat(channel.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void dropsWhatItSendsToAStreamThatIsClosedWithoutRaisingIt() throws Exception {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final LedgerPeer peer = networkOf().peerOf(channel, remote(FIRST));

		channel.close();
		peer.send(LedgerWireFixtures.status(1));

		channel.checkException();
	}

	@Example
	public void leavesAnOpenStreamAloneWhenAWriteFails() throws Exception {
		final EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
			@Override
			public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
				promise.setFailure(new IOException("The write is broken"));
			}
		});
		final LedgerPeer peer = networkOf().peerOf(channel, remote(FIRST));

		try (DebugLog log = new DebugLog(NettyLedgerPeer.class)) {
			peer.send(LedgerWireFixtures.status(1));

			assertThat(told(log),
				contains(startsWith("DEBUG Could not send to " + FIRST + ": java.io.IOException")));
		}

		channel.checkException();
		assertThat(channel.isOpen(), is(true));
	}

	private static List<String> told(final DebugLog log) {
		return log.events().stream().map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
	}

	@Property(tries = 20)
	public void completesARequestWithTheBatchOfTheReply(@ForAll @IntRange(min = 0, max = 100) int seed) {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final PendingRequests pending = new PendingRequests();
		final LedgerPeer peer = networkOf(pending).peerOf(channel, remote(FIRST));
		final BlockRequest request = LedgerWireFixtures.request(seed);
		final BlockBatch batch = LedgerWireFixtures.emptyBatch();

		final CompletableFuture<BlockBatch> reply = peer.request(request);
		final LedgerBlockRequest sent = channel.readOutbound();

		assertThat(sent.getRequest(), equalTo(request));
		assertThat(reply.isDone(), is(false));

		pending.complete(channel, LedgerBlockBatch.builder().requestId(sent.getRequestId()).batch(batch).build());
		assertThat(reply.join(), sameInstance(batch));
	}

	@Example
	public void ignoresAReplyThatComesOnAnotherChannel() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final PendingRequests pending = new PendingRequests();
		final CompletableFuture<BlockBatch> reply = networkOf(pending).peerOf(channel, remote(FIRST))
			.request(LedgerWireFixtures.request(1));
		final LedgerBlockRequest sent = channel.readOutbound();

		pending.complete(new EmbeddedChannel(), LedgerBlockBatch.builder().requestId(sent.getRequestId())
			.batch(LedgerWireFixtures.emptyBatch()).build());

		assertThat(reply.isDone(), is(false));
	}

	@Example
	public void failsARequestAtOnceWhenTheWriteFails() {
		final EmbeddedChannel channel = new EmbeddedChannel();

		channel.close();

		final CompletableFuture<BlockBatch> reply = networkOf().peerOf(channel, remote(FIRST))
			.request(LedgerWireFixtures.request(1));
		final CompletionException failure = assertThrows(CompletionException.class, () -> reply.getNow(null));

		assertThat(failure.getCause(), instanceOf(ClosedChannelException.class));
	}

	@Example
	public void failsARequestAtOnceWhenTheWriteFailsOnAnOpenChannel() {
		final EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
			@Override
			public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
				promise.setFailure(new IOException("The write is broken"));
			}
		});
		final CompletableFuture<BlockBatch> reply = networkOf().peerOf(channel, remote(FIRST))
			.request(LedgerWireFixtures.request(1));
		final CompletionException failure = assertThrows(CompletionException.class, () -> reply.getNow(null));

		assertThat(failure.getCause(), instanceOf(IOException.class));
		assertThat(channel.isOpen(), is(true));
	}

	/* The channel counts the listeners that are on its close future now */
	private static final class WatchedChannel extends EmbeddedChannel {
		private final AtomicInteger listeners = new AtomicInteger();

		@Override
		public ChannelFuture closeFuture() {
			final ChannelFuture real = super.closeFuture();

			return (ChannelFuture) Proxy.newProxyInstance(ChannelFuture.class.getClassLoader(),
				new Class<?>[] { ChannelFuture.class }, (proxy, method, arguments) -> {
					listeners.addAndGet(switch (method.getName()) {
						case "addListener" -> 1;
						case "removeListener" -> -1;
						default -> 0;
					});

					try {
						final Object result = method.invoke(real, arguments);

						return result == real ? proxy : result;
					} catch (InvocationTargetException ex) {
						throw ex.getCause();
					}
				});
		}
	}

	@Property(tries = 10)
	public void leavesNoListenerOnTheChannelOnceARequestIsDone(@ForAll @IntRange(min = 1, max = 10) int requests,
		@ForAll boolean answered) {

		final WatchedChannel channel = new WatchedChannel();
		final PendingRequests pending = new PendingRequests();
		final LedgerPeer peer = networkOf(pending).peerOf(channel, remote(FIRST));

		for (int i = 0; i < requests; i++) {
			final CompletableFuture<BlockBatch> reply = peer.request(LedgerWireFixtures.request(i));
			final LedgerBlockRequest sent = channel.readOutbound();

			assertThat(channel.listeners.get(), equalTo(1));

			if (answered) {
				pending.complete(channel, LedgerBlockBatch.builder().requestId(sent.getRequestId())
					.batch(LedgerWireFixtures.emptyBatch()).build());
			} else {
				pending.fail(sent.getRequestId(), new IOException("The request is given up"));
			}

			assertThat(reply.isDone(), is(true));
			assertThat(channel.listeners.get(), equalTo(0));
		}
	}

	@Example
	public void failsARequestAtOnceWhenTheChannelClosesWhileWaiting() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final CompletableFuture<BlockBatch> reply = networkOf().peerOf(channel, remote(FIRST))
			.request(LedgerWireFixtures.request(1));

		assertThat(reply.isDone(), is(false));
		channel.close();

		final CompletionException failure = assertThrows(CompletionException.class, () -> reply.getNow(null));

		assertThat(failure.getCause(), instanceOf(ClosedChannelException.class));
	}

	@Example
	public void numbersEachRequestOnItsOwn() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final LedgerPeer peer = networkOf().peerOf(channel, remote(FIRST));

		peer.request(LedgerWireFixtures.request(1));
		peer.request(LedgerWireFixtures.request(2));

		final LedgerBlockRequest first = channel.readOutbound();
		final LedgerBlockRequest second = channel.readOutbound();

		assertThat(first.getRequestId(), not(equalTo(second.getRequestId())));
	}

	@Example
	public void namesAPeerByTheAddressOfItsChannel() {
		assertThat(networkOf().peerOf(new EmbeddedChannel(), remote(FIRST)).id(), equalTo(FIRST));
	}

	@Property(tries = 50)
	public void namesAnIPv6PeerByItsPrefix(@ForAll long prefix, @ForAll long suffix, @ForAll long otherSuffix,
		@ForAll @IntRange(min = 0, max = 55) int bit) {

		final NettyLedgerNetwork network = networkOf();
		final String id = network.peerOf(new EmbeddedChannel(), address(prefix, suffix)).id();

		assertThat(network.peerOf(new EmbeddedChannel(), address(prefix, otherSuffix)).id(), equalTo(id));
		assertThat(network.peerOf(new EmbeddedChannel(), address(prefix ^ (1L << bit), suffix)).id(),
			not(equalTo(id)));
	}

	/* A site is given a /48 and can number 65536 subnets of /64 in it, so the 16 bits after the first 48 make no
	   quota of their own */
	@Property(tries = 50)
	public void countsTheIPv6PeersOfOneSiteUnderOneQuota(@ForAll long prefix, @ForAll long suffix,
		@ForAll @IntRange(min = 0, max = 15) int subnetBit, @ForAll @IntRange(min = 16, max = 55) int bit) {

		final NettyLedgerNetwork network = networkOf();
		final LedgerPeer peer = network.peerOf(new EmbeddedChannel(), address(prefix, suffix));
		final LedgerPeer neighbour = network.peerOf(new EmbeddedChannel(), address(prefix ^ (1L << subnetBit), suffix));
		final LedgerPeer stranger = network.peerOf(new EmbeddedChannel(), address(prefix ^ (1L << bit), suffix));

		assertThat(neighbour.quota(), equalTo(peer.quota()));
		assertThat(neighbour.id(), not(equalTo(peer.id())));
		assertThat(stranger.quota(), not(equalTo(peer.quota())));
	}

	@Example
	public void countsAnIPv4PeerUnderAQuotaOfItsOwnAddress() {
		final NettyLedgerNetwork network = networkOf();

		assertThat(network.peerOf(new EmbeddedChannel(), remote(FIRST)).quota(), equalTo(FIRST));
		assertThat(network.peerOf(new EmbeddedChannel(), remote(SECOND)).quota(), equalTo(SECOND));
	}

	/* Two nodes in one site share their limits and nothing else: each is a peer of its own, and cutting one off leaves
	   the other connected */
	@Example
	public void keepsTwoNodesOfOneSiteApartAsPeersAndClosesOnlyTheOneThatIsCutOff() {
		final EmbeddedChannel first = new EmbeddedChannel();
		final EmbeddedChannel second = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf();

		network.speakerOf(first, address(0x20010db800010001L, 1));
		network.speakerOf(second, address(0x20010db800010002L, 1));

		final List<LedgerPeer> peers = network.peers();

		assertThat(peers, hasSize(2));
		assertThat(peers.get(0).quota(), equalTo(peers.get(1).quota()));

		network.disconnect(peers.get(0).id());

		assertThat(first.isOpen() != second.isOpen(), is(true));
		assertThat(network.peers(), hasSize(1));
	}

	private static Supplier<Optional<InetSocketAddress>> mapped(final int a, final int b, final int c, final int d,
		final int port) {

		final Optional<InetSocketAddress> resolved = Optional.of(LedgerWireFixtures.ipv4Mapped(a, b, c, d, port));

		return () -> resolved;
	}

	@Property(tries = 30)
	public void namesAnIPv4AddressThatComesAsAnIPv6OneByItsIPv4Address(@ForAll @IntRange(min = 1, max = 126) int a,
		@ForAll @IntRange(min = 0, max = 255) int b, @ForAll @IntRange(min = 0, max = 255) int c,
		@ForAll @IntRange(min = 0, max = 255) int d) {

		final NettyLedgerNetwork network = networkOf();
		final String id = network.peerOf(new EmbeddedChannel(), mapped(a, b, c, d, PORT)).id();

		assertThat(id, equalTo(a + "." + b + "." + c + "." + d));
		assertThat(network.peerOf(new EmbeddedChannel(), remoteAt(id, PORT)).id(), equalTo(id));
	}

	@Property(tries = 10)
	public void namesALocalPeerThatComesAsAnIPv6AddressByItsPort(@ForAll @IntRange(min = 1, max = 65535) int port) {
		assertThat(networkOf().peerOf(new EmbeddedChannel(), mapped(127, 0, 0, 1, port)).id(),
			equalTo("127.0.0.1:" + port));
	}

	@Property(tries = 20)
	public void namesARemotePeerByItsAddressWhateverThePort(@ForAll @IntRange(min = 1, max = 65535) int port,
		@ForAll @IntRange(min = 1, max = 65535) int otherPort) {

		final NettyLedgerNetwork network = networkOf();

		assertThat(network.peerOf(new EmbeddedChannel(), remoteAt(FIRST, port)).id(), equalTo(FIRST));
		assertThat(network.peerOf(new EmbeddedChannel(), remoteAt(FIRST, otherPort)).id(), equalTo(FIRST));
	}

	@Property(tries = 20)
	public void namesALocalPeerByItsAddressAndPort(@ForAll("loopbacks") String address,
		@ForAll @IntRange(min = 1, max = 65535) int port, @ForAll @IntRange(min = 1, max = 65535) int otherPort) {

		final NettyLedgerNetwork network = networkOf();
		final String id = network.peerOf(new EmbeddedChannel(), remoteAt(address, port)).id();

		assertThat(network.peerOf(new EmbeddedChannel(), remoteAt(address, port)).id(), equalTo(id));
		assertThat(id.endsWith(":" + port), is(true));

		if (otherPort != port) {
			assertThat(network.peerOf(new EmbeddedChannel(), remoteAt(address, otherPort)).id(),
				not(equalTo(id)));
		}
	}

	@Provide
	Arbitrary<String> loopbacks() {
		return Arbitraries.of("127.0.0.1", "127.0.0.2", "127.255.255.254", "::1");
	}

	@Example
	public void listsTheLocalNodesOfOneHostAsPeersOfTheirOwn() {
		final NettyLedgerNetwork network = networkOf();
		final EmbeddedChannel first = new EmbeddedChannel();
		final EmbeddedChannel second = new EmbeddedChannel();

		network.speakerOf(first, remoteAt("127.0.0.1", PORT));
		network.speakerOf(second, remoteAt("127.0.0.1", PORT + 1));

		assertThat(network.peers(), hasSize(2));
	}

	@Example
	public void namesALocalNodeOfTheTopologyByItsPort() {
		final List<LedgerPeer> peers = networkOf(nodeWith(new EmbeddedChannel(), "127.0.0.1", PORT),
			nodeWith(new EmbeddedChannel(), "127.0.0.1", PORT + 1)).peers();

		assertThat(peers.stream().map(LedgerPeer::id).sorted().toList(),
			contains("127.0.0.1:" + PORT, "127.0.0.1:" + (PORT + 1)));
	}

	@SneakyThrows
	private static Supplier<Optional<InetSocketAddress>> address(final long prefix, final long suffix) {
		final byte[] bytes = new byte[16];

		for (int i = 0; i < Long.BYTES; i++) {
			bytes[i] = (byte) (prefix >>> (Byte.SIZE * (Long.BYTES - 1 - i)));
			bytes[Long.BYTES + i] = (byte) (suffix >>> (Byte.SIZE * (Long.BYTES - 1 - i)));
		}

		bytes[0] = 0x20;

		final Optional<InetSocketAddress> resolved = Optional.of(new InetSocketAddress(
			InetAddress.getByAddress(bytes), PORT));

		return () -> resolved;
	}

	@Example
	public void namesAPeerByTheChannelWhenTheAddressIsUnknown() {
		final EmbeddedChannel channel = new EmbeddedChannel();

		assertThat(networkOf().peerOf(channel, Optional::empty).id(), equalTo(channel.id().asLongText()));
	}

	@Example
	public void learnsTheAddressOfAPeerWhenItIsKnown() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final Supplier<Optional<InetSocketAddress>> remote = remote(FIRST);
		final boolean[] known = new boolean[1];
		final LedgerPeer peer = networkOf().peerOf(channel, () -> known[0] ? remote.get() : Optional.empty());

		assertThat(peer.id(), equalTo(channel.id().asLongText()));
		known[0] = true;
		assertThat(peer.id(), equalTo(FIRST));
	}

	@Example
	public void givesTheSamePeerForTheSameChannel() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf();

		assertThat(network.peerOf(channel, remote(FIRST)), sameInstance(network.peerOf(channel, remote(SECOND))));
	}

	@Example
	public void listsTheOpenChannelsOfTheNodesOfTheTopology() {
		final EmbeddedChannel first = new EmbeddedChannel();
		final EmbeddedChannel second = new EmbeddedChannel();
		final List<LedgerPeer> peers = networkOf(nodeWith(first, FIRST), nodeWith(second, SECOND)).peers();

		assertThat(peers.stream().map(LedgerPeer::id).sorted().toList(), contains(FIRST, SECOND));
		assertThat(writesTo(peers.stream().filter(peer -> peer.id().equals(FIRST)).findFirst().get(), first),
			is(true));
	}

	@SneakyThrows
	@Example
	public void leavesOutNodesWithoutAnOpenConnection() {
		final EmbeddedChannel closed = new EmbeddedChannel();
		final Node unconnected = Node.fromAddress(SECOND + ":" + PORT);

		closed.close();
		assertThat(networkOf(nodeWith(closed, FIRST), unconnected).peers().isEmpty(), is(true));
	}

	@Example
	public void listsTheInboundChannelsThatWereSeen() {
		final EmbeddedChannel inbound = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf();

		network.speakerOf(inbound, remote(FIRST));

		final LedgerPeer peer = onlyPeerOf(network);

		assertThat(peer.id(), equalTo(FIRST));
		assertThat(writesTo(peer, inbound), is(true));
	}

	@Property(tries = 2)
	public void countsOneNodeOnceAndPrefersTheOutboundChannel(@ForAll boolean outboundKnownFirst) {
		final EmbeddedChannel outbound = new EmbeddedChannel();
		final EmbeddedChannel inbound = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf(nodeWith(outbound, FIRST));

		if (outboundKnownFirst) {
			network.peers();
		}

		network.speakerOf(inbound, remote(FIRST));

		final LedgerPeer peer = onlyPeerOf(network);

		assertThat(writesTo(peer, outbound), is(true));
		assertThat(inbound.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void keepsTheNewestInboundChannelOfAnAddress() {
		final EmbeddedChannel older = new EmbeddedChannel();
		final EmbeddedChannel newer = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf();

		network.speakerOf(older, remote(FIRST));
		network.speakerOf(newer, remote(FIRST));

		assertThat(writesTo(onlyPeerOf(network), newer), is(true));
		assertThat(older.outboundMessages().isEmpty(), is(true));
	}

	@Property(tries = 4)
	public void prefersAStreamThisNodeOpenedWhicheverWasSeenFirst(@ForAll boolean openedFirst) {
		final EmbeddedChannel opened = new EmbeddedChannel();
		final EmbeddedChannel accepted = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf(new PendingRequests(), opened::equals, Set.of());

		network.speakerOf(openedFirst ? opened : accepted, remote(FIRST));
		network.speakerOf(openedFirst ? accepted : opened, remote(FIRST));

		assertThat(writesTo(onlyPeerOf(network), opened), is(true));
		assertThat(accepted.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void tellsTheDriverWhichPeersThisNodeDialled() {
		final EmbeddedChannel opened = new EmbeddedChannel();
		final EmbeddedChannel accepted = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf(new PendingRequests(), opened::equals, Set.of());

		assertThat(network.speakerOf(opened, remote(FIRST)).dialled(), is(true));
		assertThat(network.speakerOf(accepted, remote(SECOND)).dialled(), is(false));
	}

	@Example
	public void takesAStreamThisNodeOpenedEvenWhenTheTopologyHoldsAnotherOfTheNode() {
		final EmbeddedChannel opened = new EmbeddedChannel();
		final EmbeddedChannel accepted = new EmbeddedChannel();
		final Node announced = nodeWith(accepted, FIRST);
		final NettyLedgerNetwork network = networkOf(new PendingRequests(), opened::equals, Set.of(announced));

		network.speakerOf(opened, remote(FIRST));

		assertThat(writesTo(onlyPeerOf(network), opened), is(true));
		assertThat(accepted.outboundMessages().isEmpty(), is(true));
	}

	@Property(tries = 20)
	public void listsThePeersInTheOrderOfTheirIds(@ForAll @IntRange(min = 2, max = 8) int count, @ForAll long seed) {
		final List<Integer> hosts = new ArrayList<>(IntStream.rangeClosed(1, count).boxed().toList());
		final NettyLedgerNetwork network = networkOf();

		Collections.shuffle(hosts, new Random(seed));
		hosts.forEach(host -> network.speakerOf(new EmbeddedChannel(), remote("10.0.0." + host)));

		final List<String> ids = network.peers().stream().map(LedgerPeer::id).toList();

		assertThat(ids, equalTo(ids.stream().sorted().toList()));
		assertThat(ids, hasSize(count));
	}

	@Property(tries = 10)
	public void choosesTheSameStreamWhateverTheOrderOfTheTopology(@ForAll long seed) {
		final EmbeddedChannel low = new EmbeddedChannel();
		final EmbeddedChannel high = new EmbeddedChannel();
		final List<Node> nodes = new ArrayList<>(List.of(nodeWith(low, FIRST, PORT),
			nodeWith(high, FIRST, PORT + 1)));

		Collections.shuffle(nodes, new Random(seed));

		final NettyLedgerNetwork network = networkOf(new PendingRequests(), channel -> true,
			new LinkedHashSet<>(nodes));

		assertThat(writesTo(onlyPeerOf(network), high), is(true));
		assertThat(low.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void keepsAnInboundChannelOfAnotherAddressApart() {
		final NettyLedgerNetwork network = networkOf(nodeWith(new EmbeddedChannel(), FIRST));

		network.speakerOf(new EmbeddedChannel(), remote(SECOND));

		assertThat(network.peers().stream().map(LedgerPeer::id).sorted().toList(), contains(FIRST, SECOND));
	}

	/* The gate: only a stream whose connection settled on the ledger protocol is a peer, is sent to, is asked */
	@Example
	public void listsNoStreamThatDidNotSettleOnTheLedgerProtocol() {
		final EmbeddedChannel agreeing = new EmbeddedChannel();
		final EmbeddedChannel unagreeing = new EmbeddedChannel();
		final EmbeddedChannel unagreeingSpeaker = new EmbeddedChannel();
		final Node dialledAgreeing = nodeWith(agreeing, FIRST);
		final Node dialledUnagreeing = nodeWith(unagreeing, SECOND);
		final NettyLedgerNetwork network = networkOf(new PendingRequests(), channel -> true,
			channel -> channel == agreeing, Set.of(dialledAgreeing, dialledUnagreeing));

		network.speakerOf(unagreeingSpeaker, remote("10.0.0.3"));

		final List<LedgerPeer> peers = network.peers();

		assertThat(peers.stream().map(LedgerPeer::id).toList(), contains(FIRST));
		assertThat(network.speaks(agreeing), is(true));
		assertThat(network.speaks(unagreeing), is(false));
		assertThat(writesTo(peers.get(0), agreeing), is(true));
	}

	@Property(tries = 10)
	public void sendsNothingToAStreamThatDidNotSettleOnTheLedgerProtocol(
		@ForAll @IntRange(min = 0, max = 100) int seed) {

		final EmbeddedChannel channel = new EmbeddedChannel();
		final LedgerPeer peer = networkOf(new PendingRequests(), c -> false, c -> false, Set.of())
			.peerOf(channel, remote(FIRST));

		try (DebugLog log = new DebugLog(NettyLedgerPeer.class)) {
			peer.send(LedgerWireFixtures.status(seed));
			peer.send(LedgerWireFixtures.transaction(seed));

			final String dropped = "DEBUG Dropped what was sent to " + FIRST + ": it does not speak the ledger";

			assertThat(told(log), contains(dropped, dropped));
		}

		assertThat(channel.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void asksNoStreamForBlocksThatDidNotSettleOnTheLedgerProtocol() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final LedgerPeer peer = networkOf(new PendingRequests(), c -> false, c -> false, Set.of())
			.peerOf(channel, remote(FIRST));
		final CompletableFuture<BlockBatch> reply = peer.request(LedgerWireFixtures.request(1));
		final CompletionException failure = assertThrows(CompletionException.class, () -> reply.getNow(null));

		assertThat(failure.getCause(), instanceOf(IllegalStateException.class));
		assertThat(channel.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void sendsToAStreamOnceItsConnectionHasSettledOnTheLedgerProtocol() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final boolean[] settled = new boolean[1];
		final LedgerPeer peer = networkOf(new PendingRequests(), c -> false, c -> settled[0], Set.of())
			.peerOf(channel, remote(FIRST));

		peer.send(LedgerWireFixtures.status(1));
		assertThat(channel.outboundMessages().isEmpty(), is(true));

		settled[0] = true;
		peer.send(LedgerWireFixtures.status(1));
		assertThat(channel.outboundMessages().size(), is(1));
	}

	@Example
	public void sendsToNoStreamBeforeItHasSpoken() {
		final NettyLedgerNetwork network = networkOf();
		final EmbeddedChannel stream = new EmbeddedChannel();

		network.peerOf(stream, remote(FIRST));
		assertThat(network.peers().isEmpty(), is(true));
		assertThat(network.tracked(), equalTo(0));

		network.speakerOf(stream, remote(FIRST));
		network.speakerOf(stream, remote(FIRST));
		assertThat(network.peers(), hasSize(1));
		assertThat(network.tracked(), equalTo(1));
	}

	@Property(tries = 5)
	public void keepsTheNewestSpeakersAndEveryNodeOfTheTopology(
		@ForAll @IntRange(min = 1, max = 100) int surplus) {

		final EmbeddedChannel dialled = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf(nodeWith(dialled, FIRST));
		final int speakers = NettyLedgerNetwork.MAX_SPEAKERS + surplus;

		IntStream.range(0, speakers).forEach(i -> network.speakerOf(new EmbeddedChannel(),
			remote("10.1." + i + ".1")));

		final List<String> ids = network.peers().stream().map(LedgerPeer::id).toList();

		assertThat(ids, hasSize(NettyLedgerNetwork.MAX_SPEAKERS + 1));
		assertThat(ids.contains(FIRST), is(true));
		assertThat(ids.contains("10.1." + (speakers - 1) + ".1"), is(true));
		assertThat(ids.contains("10.1." + (surplus - 1) + ".1"), is(false));
		assertThat(ids.contains("10.1." + surplus + ".1"), is(true));
		assertThat(network.tracked(), equalTo(NettyLedgerNetwork.MAX_SPEAKERS));
	}

	@Property(tries = 10)
	public void forgetsEveryChannelThatCloses(@ForAll @IntRange(min = 1, max = 20) int count) {
		final NettyLedgerNetwork network = networkOf();
		final List<EmbeddedChannel> channels = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			channels.add(new EmbeddedChannel());
			network.speakerOf(channels.get(i), remote("10.2.0." + i));
		}

		assertThat(network.tracked(), equalTo(count));
		channels.forEach(EmbeddedChannel::close);
		assertThat(network.tracked(), equalTo(0));
	}

	@Example
	public void dropsAnInboundChannelWhenItCloses() {
		final EmbeddedChannel inbound = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf();

		network.speakerOf(inbound, remote(FIRST));
		assertThat(network.peers(), hasSize(1));

		inbound.close();
		assertThat(network.peers().isEmpty(), is(true));
	}

	@Example
	public void closesEveryStreamOfADisconnectedIdAndNoOther() {
		final EmbeddedChannel dialled = new EmbeddedChannel();
		final EmbeddedChannel older = new EmbeddedChannel();
		final EmbeddedChannel newer = new EmbeddedChannel();
		final EmbeddedChannel other = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf(nodeWith(dialled, FIRST));

		network.speakerOf(older, remote(FIRST));
		network.speakerOf(newer, remote(FIRST));
		network.speakerOf(other, remote(SECOND));
		network.disconnect(FIRST);

		assertThat(Stream.of(dialled, older, newer, other).map(EmbeddedChannel::isOpen).toList(),
			contains(false, false, false, true));
		assertThat(network.peers().stream().map(LedgerPeer::id).toList(), contains(SECOND));
	}

	@Example
	public void closesNothingForAnIdItHasNoStreamOf() {
		final EmbeddedChannel dialled = new EmbeddedChannel();
		final EmbeddedChannel inbound = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf(nodeWith(dialled, FIRST));

		network.speakerOf(inbound, remote(SECOND));
		network.disconnect("10.9.9.9");

		assertThat(dialled.isOpen(), is(true));
		assertThat(inbound.isOpen(), is(true));
	}

	@Example
	public void leavesAStreamAloneThatDidNotSettleOnTheLedgerProtocol() {
		final EmbeddedChannel agreeing = new EmbeddedChannel();
		final EmbeddedChannel unagreeing = new EmbeddedChannel();
		final Set<Node> dialled = Set.of(nodeWith(agreeing, FIRST), nodeWith(unagreeing, FIRST, PORT + 1));
		final NettyLedgerNetwork network = networkOf(new PendingRequests(), channel -> true,
			channel -> channel == agreeing, dialled);

		network.disconnect(FIRST);

		assertThat(agreeing.isOpen(), is(false));
		assertThat(unagreeing.isOpen(), is(true));
	}

	@Example
	public void closesTheOtherStreamsWhenOneCannotBeNamed() {
		final EmbeddedChannel unnamed = new EmbeddedChannel();
		final EmbeddedChannel named = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf();
		final boolean[] forgotten = new boolean[1];

		network.speakerOf(unnamed, () -> {
			if (forgotten[0]) {
				throw new IllegalStateException("No address");
			}

			return remote(FIRST).get();
		});
		network.speakerOf(named, remote(FIRST));
		forgotten[0] = true;
		network.disconnect(FIRST);

		assertThat(named.isOpen(), is(false));
		assertThat(unnamed.isOpen(), is(true));
	}

	@Example
	public void readsTheAddressOfEachNodeOfTheTopologyAFewTimesAndOfNoneThatIsNotConnected() {
		final int count = 64;
		final List<WatchedNode> connected = IntStream.range(0, count).mapToObj(i -> new WatchedNode("10.5.0." + i,
			Optional.of(connectionTo(new EmbeddedChannel())))).toList();
		final EmbeddedChannel gone = new EmbeddedChannel();
		final List<WatchedNode> idle = IntStream.range(0, count).mapToObj(i -> new WatchedNode("10.6.0." + i,
			i % 2 == 0 ? Optional.empty() : Optional.of(connectionTo(gone)))).toList();
		final Set<Node> nodes = new LinkedHashSet<>(Stream.concat(connected.stream(), idle.stream()).toList());
		final NettyLedgerNetwork network = networkOf(new PendingRequests(), channel -> true, nodes);

		gone.close();
		Stream.concat(connected.stream(), idle.stream()).forEach(node -> node.reads = 0);

		assertThat(network.peers(), hasSize(count));
		assertThat(connected.stream().mapToInt(node -> node.reads).max().getAsInt() <= 4, is(true));
		assertThat(idle.stream().mapToInt(node -> node.reads).sum(), is(0));
	}

	@Property(tries = 5)
	public void keepsTheNewestStreamsOfAnIdAndForgetsTheOldest(
		@ForAll @IntRange(min = 1, max = 20) int surplus) {

		final NettyLedgerNetwork network = networkOf();
		final List<EmbeddedChannel> channels = IntStream.range(0, NettyLedgerNetwork.MAX_PER_ID + surplus)
			.mapToObj(i -> new EmbeddedChannel()).toList();

		channels.forEach(channel -> network.speakerOf(channel, remote(FIRST)));
		assertThat(network.tracked(), equalTo(NettyLedgerNetwork.MAX_PER_ID));

		channels.subList(surplus, channels.size()).forEach(EmbeddedChannel::close);
		assertThat(network.peers().isEmpty(), is(true));
	}

	@Property(tries = 5)
	public void keepsTheSpeakersOfOtherIdsWhenOneIdOpensManyStreams(
		@ForAll @IntRange(min = 1, max = 100) int flood) {

		final NettyLedgerNetwork network = networkOf();
		final int others = NettyLedgerNetwork.MAX_SPEAKERS - NettyLedgerNetwork.MAX_PER_ID;

		IntStream.range(0, others).forEach(i -> network.speakerOf(new EmbeddedChannel(), remote("10.1." + i + ".1")));
		IntStream.range(0, flood).forEach(i -> network.speakerOf(new EmbeddedChannel(), remote(FIRST)));

		final List<String> ids = network.peers().stream().map(LedgerPeer::id).toList();

		assertThat(ids, hasSize(others + 1));
		assertThat(ids.contains("10.1.0.1"), is(true));
		assertThat(network.tracked(), equalTo(others + Math.min(flood, NettyLedgerNetwork.MAX_PER_ID)));
	}

	@Example
	public void keepsAnEvictedStreamAgainWhenItNextSaysSomething() {
		final NettyLedgerNetwork network = networkOf();
		final EmbeddedChannel evicted = new EmbeddedChannel();
		final EmbeddedChannel middle = new EmbeddedChannel();
		final EmbeddedChannel newest = new EmbeddedChannel();

		network.speakerOf(evicted, remote(FIRST));
		network.speakerOf(middle, remote(FIRST));
		network.speakerOf(newest, remote(FIRST));
		assertThat(network.tracked(), equalTo(NettyLedgerNetwork.MAX_PER_ID));

		network.speakerOf(evicted, remote(FIRST));
		assertThat(network.tracked(), equalTo(NettyLedgerNetwork.MAX_PER_ID));

		newest.close();
		assertThat(writesTo(onlyPeerOf(network), evicted), is(true));
		assertThat(middle.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void keepsAnEvictedSpeakerAgainWhenTheNetworkWasFull() {
		final NettyLedgerNetwork network = networkOf();
		final EmbeddedChannel first = new EmbeddedChannel();

		network.speakerOf(first, remote("10.1.0.1"));
		IntStream.range(1, NettyLedgerNetwork.MAX_SPEAKERS + 1)
			.forEach(i -> network.speakerOf(new EmbeddedChannel(), remote("10.1." + i + ".1")));
		assertThat(network.peers().stream().map(LedgerPeer::id).toList().contains("10.1.0.1"), is(false));

		network.speakerOf(first, remote("10.1.0.1"));

		final List<String> ids = network.peers().stream().map(LedgerPeer::id).toList();

		assertThat(ids.contains("10.1.0.1"), is(true));
		assertThat(ids.contains("10.1.1.1"), is(false));
		assertThat(network.tracked(), equalTo(NettyLedgerNetwork.MAX_SPEAKERS));
	}
}
