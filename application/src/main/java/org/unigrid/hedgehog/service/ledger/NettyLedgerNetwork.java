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
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.AttributeKey;
import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.ledger.LedgerNetwork;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.PendingRequests;

/* The peers are the open streams of the nodes of the topology and the newest streams that peers opened to this node
   and that have spoken the ledger protocol, and only those whose connection settled on the ledger protocol (see
   LedgerProtocol): a node of an earlier release is in the topology and is no peer of the ledger. One node can be on
   both, and is one peer: the driver keeps its limits and its "not back to where it came from" by id, and sending
   everything twice to one node serves no one. Of the streams of one id the one this node opened wins, and among
   equals the newest, as the older may be a dead one that has not been noticed yet. The peers come in the order of
   their ids, whatever the order of the topology. */
@RequiredArgsConstructor
public class NettyLedgerNetwork implements LedgerNetwork {
	public static final int MAX_SPEAKERS = 64;
	private static final AttributeKey<NettyLedgerPeer> PEER_KEY = AttributeKey.valueOf("LEDGER_PEER");

	private final Supplier<Set<Node>> nodes;
	private final PendingRequests pending;
	private final Predicate<Channel> outbound;
	private final Predicate<Channel> negotiated;
	private final BlockingDeque<NettyLedgerPeer> seen = new LinkedBlockingDeque<>(MAX_SPEAKERS);
	private final AtomicLong registrations = new AtomicLong();

	/* A stream is opened by this node when it was the one to create it, which is what dialling a node does */
	public static boolean openedHere(final Channel channel) {
		return channel instanceof QuicStreamChannel stream && stream.isLocalCreated();
	}

	/* One peer for each stream, made the first time it is asked for. It is not yet someone to send to: a stream
	   that has said nothing may be anyone that connected. */
	public NettyLedgerPeer peerOf(final Channel channel, final Supplier<Optional<InetSocketAddress>> remote) {
		final NettyLedgerPeer known = channel.attr(PEER_KEY).get();

		return known != null ? known : create(channel, remote);
	}

	/* The peer of a stream that has said something of the ledger protocol, which is when it is someone to send to. Of
	   such streams that nodes opened to this one the newest are kept, so that what is sent for each message and what
	   is held cannot be made to grow by opening streams. */
	public NettyLedgerPeer speakerOf(final Channel channel, final Supplier<Optional<InetSocketAddress>> remote) {
		final NettyLedgerPeer peer = peerOf(channel, remote);

		if (peer.firstWord()) {
			while (!seen.offerLast(peer)) {
				seen.pollFirst();
			}

			channel.closeFuture().addListener(closed -> seen.remove(peer));
		}

		return peer;
	}

	private NettyLedgerPeer create(final Channel channel, final Supplier<Optional<InetSocketAddress>> remote) {
		final NettyLedgerPeer created = new NettyLedgerPeer(channel, remote, pending,
			registrations.incrementAndGet(), negotiated);
		final NettyLedgerPeer raced = channel.attr(PEER_KEY).setIfAbsent(created);

		return raced != null ? raced : created;
	}

	/* For the tests: what is held on to, which must not grow with the streams that came and went */
	int tracked() {
		return seen.size();
	}

	@Override
	public List<LedgerPeer> peers() {
		final Map<String, NettyLedgerPeer> byId = new TreeMap<>();

		Stream.concat(seen.stream(), connected()).filter(peer -> peer.channel().isActive() && peer.speaksLedger())
			.forEach(peer -> byId.merge(peer.id(), peer, this::preferred));
		return List.copyOf(byId.values());
	}

	/* Whether the connection of the stream settled on the ledger protocol: the capability gate of the ledger */
	public boolean speaks(final Channel stream) {
		return negotiated.test(stream);
	}

	private NettyLedgerPeer preferred(final NettyLedgerPeer one, final NettyLedgerPeer other) {
		final boolean oneOpenedHere = outbound.test(one.channel());

		if (oneOpenedHere != outbound.test(other.channel())) {
			return oneOpenedHere ? one : other;
		}

		return one.order() > other.order() ? one : other;
	}

	/* In the order of the addresses, so that the streams of the nodes are first seen in the same order every time */
	private Stream<NettyLedgerPeer> connected() {
		return nodes.get().stream().sorted(Comparator.comparing(node -> String.valueOf(node.getAddress())))
			.flatMap(node -> node.getConnection().map(Connection::getChannel)
				.map(channel -> peerOf(channel, () -> addressOf(node))).stream());
	}

	private static Optional<InetSocketAddress> addressOf(final Node node) {
		return Optional.ofNullable(node.getAddress()).filter(address -> address.getAddress() != null);
	}
}
