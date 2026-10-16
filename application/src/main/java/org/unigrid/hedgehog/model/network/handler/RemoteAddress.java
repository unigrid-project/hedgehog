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

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Optional;
import org.unigrid.hedgehog.service.ledger.NettyLedgerPeer;

public final class RemoteAddress {
	private static final AttributeKey<Integer> LISTEN_PORT_KEY = AttributeKey.valueOf("LISTEN_PORT");

	private RemoteAddress() {
		/* Static helpers only */
	}

	/* The first port that a peer says it listens on stands for the connection: a peer that said another one later
	   would only rename itself */
	public static void listensOn(final Channel stream, final int port) {
		if (port > 0 && Objects.nonNull(stream.parent())) {
			stream.parent().attr(LISTEN_PORT_KEY).setIfAbsent(port);
		}
	}

	/* The address is kept on the QUIC connection that the stream belongs to. A server learns it from the connection
	   event, which follows the peer when it moves; a client knows it from where it connected. The port a server
	   learns is its own, assumed for every peer, so a peer on this host cannot be told from another by it: such a
	   peer is read from the socket of its connection, and from the port it listens on once it has said which.
	   A forwarder on the loopback address hands every connection to this node as one from this host, so each gets
	   an id of its own and with it buckets and a score of its own in the driver: the clients behind it are not held
	   to one limit. */
	public static Optional<InetSocketAddress> of(final Channel stream) {
		return Optional.ofNullable(stream.parent()).flatMap(connection -> {
			final Optional<InetSocketAddress> learned = resolved(
				connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).get());
			final Optional<InetSocketAddress> connected = resolved(
				connection.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).get());

			return learned.filter(socket -> !socket.getAddress().isLoopbackAddress())
				.or(() -> connected.or(() -> learned).map(socket -> listening(connection, socket)));
		});
	}

	private static InetSocketAddress listening(final Channel connection, final InetSocketAddress socket) {
		final Integer port = connection.attr(LISTEN_PORT_KEY).get();

		return port == null || !socket.getAddress().isLoopbackAddress() ? socket
			: new InetSocketAddress(socket.getAddress(), port);
	}

	private static Optional<InetSocketAddress> resolved(final SocketAddress address) {
		return Optional.ofNullable(address).filter(InetSocketAddress.class::isInstance)
			.map(InetSocketAddress.class::cast).filter(socket -> Objects.nonNull(socket.getAddress()))
			.map(socket -> new InetSocketAddress(NettyLedgerPeer.plain(socket.getAddress()), socket.getPort()));
	}
}
