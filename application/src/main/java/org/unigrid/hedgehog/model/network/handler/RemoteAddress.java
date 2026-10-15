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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Optional;

public final class RemoteAddress {
	private RemoteAddress() {
		/* Static helpers only */
	}

	/* The address is kept on the QUIC connection that the stream belongs to. A server learns it from the connection
	   event, which follows the peer when it moves; a client knows it from where it connected. The port a server
	   learns is its own, assumed for every peer, so a peer on this host cannot be told from another by it: such a
	   peer is read from the socket of its connection, where the port is its own. */
	public static Optional<InetSocketAddress> of(final Channel stream) {
		return Optional.ofNullable(stream.parent()).flatMap(connection -> {
			final Optional<InetSocketAddress> learned = resolved(
				connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).get());
			final Optional<InetSocketAddress> connected = resolved(
				connection.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).get());

			return learned.filter(socket -> !socket.getAddress().isLoopbackAddress()).or(() -> connected)
				.or(() -> learned);
		});
	}

	private static Optional<InetSocketAddress> resolved(final SocketAddress address) {
		return Optional.ofNullable(address).filter(InetSocketAddress.class::isInstance)
			.map(InetSocketAddress.class::cast).filter(socket -> Objects.nonNull(socket.getAddress()));
	}
}
