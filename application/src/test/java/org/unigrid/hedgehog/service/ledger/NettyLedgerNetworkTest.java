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
import io.netty.channel.embedded.EmbeddedChannel;
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
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
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

	private static Supplier<Optional<InetSocketAddress>> remote(final String address, final int port) {
		final Optional<InetSocketAddress> resolved = Optional.of(new InetSocketAddress(address, port));

		return () -> resolved;
	}

	private static Supplier<Optional<InetSocketAddress>> remote(final String address) {
		return remote(address, PORT);
	}

	private static Node nodeWith(final Channel channel, final String address) {
		return nodeWith(channel, address, PORT);
	}

	@SneakyThrows
	private static Node nodeWith(final Channel channel, final String address, final int port) {
		final Node node = Node.fromAddress(address + ":" + port);

		node.setConnection(Optional.of(new Connection() {
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
		}));

		return node;
	}

	private static boolean connectionOf(final Node node, final Channel channel) {
		return node.getConnection().map(Connection::getChannel).filter(held -> held == channel).isPresent();
	}

	private static NettyLedgerNetwork networkOf(final PendingRequests pending, final Predicate<Channel> outbound,
		final Set<Node> nodes) {

		return new NettyLedgerNetwork(() -> nodes, pending, outbound);
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
			LedgerConsensus.builder().message(message).build(), LedgerHeartbeat.builder().heartbeat(heartbeat).build(),
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
	public void raisesAFailedWriteOnTheChannel() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final LedgerPeer peer = networkOf().peerOf(channel, remote(FIRST));

		channel.close();
		peer.send(LedgerWireFixtures.status(1));

		assertThrows(ClosedChannelException.class, channel::checkException);
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
		assertThat(network.peerOf(new EmbeddedChannel(), address(prefix ^ (1L << bit), suffix)).id(), not(equalTo(id)));
	}

	@Property(tries = 20)
	public void namesARemotePeerByItsAddressWhateverThePort(@ForAll @IntRange(min = 1, max = 65535) int port,
		@ForAll @IntRange(min = 1, max = 65535) int otherPort) {

		final NettyLedgerNetwork network = networkOf();

		assertThat(network.peerOf(new EmbeddedChannel(), remote(FIRST, port)).id(), equalTo(FIRST));
		assertThat(network.peerOf(new EmbeddedChannel(), remote(FIRST, otherPort)).id(), equalTo(FIRST));
	}

	@Property(tries = 20)
	public void namesALocalPeerByItsAddressAndPort(@ForAll("loopbacks") String address,
		@ForAll @IntRange(min = 1, max = 65535) int port, @ForAll @IntRange(min = 1, max = 65535) int otherPort) {

		final NettyLedgerNetwork network = networkOf();
		final String id = network.peerOf(new EmbeddedChannel(), remote(address, port)).id();

		assertThat(network.peerOf(new EmbeddedChannel(), remote(address, port)).id(), equalTo(id));
		assertThat(id.endsWith(":" + port), is(true));

		if (otherPort != port) {
			assertThat(network.peerOf(new EmbeddedChannel(), remote(address, otherPort)).id(), not(equalTo(id)));
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

		network.peerOf(first, remote("127.0.0.1", PORT));
		network.peerOf(second, remote("127.0.0.1", PORT + 1));

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
		assertThat(writesTo(peers.stream().filter(peer -> peer.id().equals(FIRST)).findFirst().get(), first), is(true));
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

		network.peerOf(inbound, remote(FIRST));

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

		network.peerOf(inbound, remote(FIRST));

		final LedgerPeer peer = onlyPeerOf(network);

		assertThat(writesTo(peer, outbound), is(true));
		assertThat(inbound.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void keepsTheNewestInboundChannelOfAnAddress() {
		final EmbeddedChannel older = new EmbeddedChannel();
		final EmbeddedChannel newer = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf();

		network.peerOf(older, remote(FIRST));
		network.peerOf(newer, remote(FIRST));

		assertThat(writesTo(onlyPeerOf(network), newer), is(true));
		assertThat(older.outboundMessages().isEmpty(), is(true));
	}

	@Property(tries = 4)
	public void prefersAStreamThisNodeOpenedWhicheverWasSeenFirst(@ForAll boolean openedFirst) {
		final EmbeddedChannel opened = new EmbeddedChannel();
		final EmbeddedChannel accepted = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf(new PendingRequests(), opened::equals, Set.of());

		network.peerOf(openedFirst ? opened : accepted, remote(FIRST));
		network.peerOf(openedFirst ? accepted : opened, remote(FIRST));

		assertThat(writesTo(onlyPeerOf(network), opened), is(true));
		assertThat(accepted.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void takesAStreamThisNodeOpenedEvenWhenTheTopologyHoldsAnotherOfTheNode() {
		final EmbeddedChannel opened = new EmbeddedChannel();
		final EmbeddedChannel accepted = new EmbeddedChannel();
		final Node announced = nodeWith(accepted, FIRST);
		final NettyLedgerNetwork network = networkOf(new PendingRequests(), opened::equals, Set.of(announced));

		network.peerOf(opened, remote(FIRST));

		assertThat(writesTo(onlyPeerOf(network), opened), is(true));
		assertThat(accepted.outboundMessages().isEmpty(), is(true));
	}

	@Property(tries = 20)
	public void listsThePeersInTheOrderOfTheirIds(@ForAll @IntRange(min = 2, max = 8) int count, @ForAll long seed) {
		final List<Integer> hosts = new ArrayList<>(IntStream.rangeClosed(1, count).boxed().toList());
		final NettyLedgerNetwork network = networkOf();

		Collections.shuffle(hosts, new Random(seed));
		hosts.forEach(host -> network.peerOf(new EmbeddedChannel(), remote("10.0.0." + host)));

		final List<String> ids = network.peers().stream().map(LedgerPeer::id).toList();

		assertThat(ids, equalTo(ids.stream().sorted().toList()));
		assertThat(ids, hasSize(count));
	}

	@Property(tries = 10)
	public void choosesTheSameStreamWhateverTheOrderOfTheTopology(@ForAll long seed) {
		final EmbeddedChannel low = new EmbeddedChannel();
		final EmbeddedChannel high = new EmbeddedChannel();
		final List<Node> nodes = new ArrayList<>(List.of(nodeWith(low, FIRST, PORT), nodeWith(high, FIRST, PORT + 1)));

		Collections.shuffle(nodes, new Random(seed));

		final NettyLedgerNetwork network = networkOf(new PendingRequests(), channel -> true,
			new LinkedHashSet<>(nodes));

		assertThat(writesTo(onlyPeerOf(network), high), is(true));
		assertThat(low.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void keepsAnInboundChannelOfAnotherAddressApart() {
		final NettyLedgerNetwork network = networkOf(nodeWith(new EmbeddedChannel(), FIRST));

		network.peerOf(new EmbeddedChannel(), remote(SECOND));

		assertThat(network.peers().stream().map(LedgerPeer::id).sorted().toList(), contains(FIRST, SECOND));
	}

	@Property(tries = 10)
	public void forgetsEveryChannelThatCloses(@ForAll @IntRange(min = 1, max = 20) int count) {
		final NettyLedgerNetwork network = networkOf();
		final List<EmbeddedChannel> channels = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			channels.add(new EmbeddedChannel());
			network.peerOf(channels.get(i), remote(FIRST));
		}

		assertThat(network.tracked(), equalTo(count));
		channels.forEach(EmbeddedChannel::close);
		assertThat(network.tracked(), equalTo(0));
	}

	@Example
	public void dropsAnInboundChannelWhenItCloses() {
		final EmbeddedChannel inbound = new EmbeddedChannel();
		final NettyLedgerNetwork network = networkOf();

		network.peerOf(inbound, remote(FIRST));
		assertThat(network.peers(), hasSize(1));

		inbound.close();
		assertThat(network.peers().isEmpty(), is(true));
	}
}
