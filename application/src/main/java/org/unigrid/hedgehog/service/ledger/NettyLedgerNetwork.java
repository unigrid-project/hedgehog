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
import io.netty.util.AttributeKey;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.ledger.LedgerNetwork;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.PendingRequests;

/* The peers are the open streams of the nodes of the topology and the streams that peers opened to this node and
   that have spoken the ledger protocol. One node can be on both, and is one peer: the driver keeps its limits and
   its "not back to where it came from" by address, and sending everything twice to one node serves no one. The
   stream this node opened wins, and among those a peer opened the newest, as the older may be a dead one that has
   not been noticed yet. */
@RequiredArgsConstructor
public class NettyLedgerNetwork implements LedgerNetwork {
	private static final AttributeKey<NettyLedgerPeer> PEER_KEY = AttributeKey.valueOf("LEDGER_PEER");

	private final Supplier<Set<Node>> nodes;
	private final PendingRequests pending;
	private final Queue<NettyLedgerPeer> seen = new ConcurrentLinkedQueue<>();

	/* One peer for each stream, made the first time it is asked for */
	public NettyLedgerPeer peerOf(final Channel channel, final Supplier<Optional<InetAddress>> remote) {
		final NettyLedgerPeer known = channel.attr(PEER_KEY).get();

		return known != null ? known : register(channel, remote);
	}

	private NettyLedgerPeer register(final Channel channel, final Supplier<Optional<InetAddress>> remote) {
		final NettyLedgerPeer created = new NettyLedgerPeer(channel, remote, pending);
		final NettyLedgerPeer raced = channel.attr(PEER_KEY).setIfAbsent(created);

		if (raced != null) {
			return raced;
		}

		seen.add(created);
		channel.closeFuture().addListener(closed -> seen.remove(created));
		return created;
	}

	/* For the tests: what is held on to, which must not grow with the streams that came and went */
	int tracked() {
		return seen.size();
	}

	@Override
	public List<LedgerPeer> peers() {
		final Map<String, LedgerPeer> byAddress = new LinkedHashMap<>();

		Stream.concat(seen.stream(), connected()).filter(peer -> peer.channel().isActive())
			.forEach(peer -> byAddress.put(peer.id(), peer));
		return List.copyOf(byAddress.values());
	}

	private Stream<NettyLedgerPeer> connected() {
		return nodes.get().stream().flatMap(node -> node.getConnection().map(Connection::getChannel)
			.map(channel -> peerOf(channel, () -> addressOf(node))).stream());
	}

	private static Optional<InetAddress> addressOf(final Node node) {
		return Optional.ofNullable(node.getAddress()).map(InetSocketAddress::getAddress);
	}
}
