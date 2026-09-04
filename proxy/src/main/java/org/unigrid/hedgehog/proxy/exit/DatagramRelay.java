/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.proxy.exit;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.unigrid.hedgehog.proxy.session.Datagram;
import org.unigrid.hedgehog.proxy.session.Session;
import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* The datagram side of an exit relay: what the client may send through the exit's socket, and what may come back.
   The relay calls it under its own lock and the destinations call the socket under theirs, from a lookup thread
   too, so the socket must never wait. What is dropped is counted and never logged one by one, as strangers decide
   how much of it there is. */
final class DatagramRelay {
	/* The drops of the relay's own, past which the association gets its one warning */
	static final long WARN_AFTER_DROPS = 1_000;

	private final Session session;
	private final UdpConnector.Socket socket;
	private final Logger log;
	private final DatagramDestinations destinations;
	private final AtomicLong dropped = new AtomicLong();

	DatagramRelay(Session session, ExitContext context, UdpConnector.Socket socket, Logger log) {
		this.session = session;
		this.socket = socket;
		this.log = log;
		destinations = new DatagramDestinations(context.policy(), context.resolver(), context.resolving(),
			context.clock(), (address, port, bytes) -> {
				if (!socket.send(address, port, bytes)) {
					drop();
				}
			});
	}

	/* The destinations' drops and the relay's own: strangers, replies too large for an entry, what the socket
	   refused */
	long dropped() {
		return dropped.get() + destinations.dropped();
	}

	void setReadable(boolean readable) {
		socket.setReadable(readable);
	}

	void close() {
		socket.close();
	}

	/* Asked before the connector copies a datagram's content: a source the association never sent to is dropped */
	boolean accepts(InetSocketAddress source) {
		if (destinations.allowsReplyFrom(source)) {
			return true;
		}

		drop();
		return false;
	}

	/* The session only opens a datagram that fits an entry, so there is no size to check on the way out */
	void forward(Datagram datagram) {
		destinations.send(datagram.address(), datagram.bytes());
	}

	/* The datagram for the client, from the address it came from, when the association sent there and it fits one
	   entry */
	Optional<Datagram> reply(InetSocketAddress source, byte[] bytes) {
		if (destinations.allowsReplyFrom(source)) {
			final SocksAddress address = ExitRelay.addressOf(source);

			if (bytes.length <= session.maxDataBytes() - address.encodedSize()) {
				return Optional.of(new Datagram(address, bytes));
			}
		}

		drop();
		return Optional.empty();
	}

	private void drop() {
		if (dropped.incrementAndGet() == WARN_AFTER_DROPS + 1) {
			log.warn("An exit relay has dropped more than {} datagrams", WARN_AFTER_DROPS);
		}
	}
}
