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

package org.unigrid.hedgehog.proxy.exit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/* Records the sockets the relay asks for; the test opens them, and answers through the listener */
final class FakeUdpConnector implements UdpConnector {
	final List<Listener> requests = new ArrayList<>();

	@Override
	public void open(Listener listener) {
		requests.add(listener);
	}

	Listener last() {
		return requests.get(requests.size() - 1);
	}

	/* A datagram that reaches the socket, handed over as the real connector does: only when its source is accepted */
	void arrive(InetSocketAddress source, byte[] bytes) {
		if (last().accepts(source)) {
			last().received(source, bytes);
		}
	}

	/* A datagram socket held in memory */
	static final class FakeSocket implements Socket {
		record Sent(InetAddress address, int port, byte[] bytes) {
			/* One datagram the relay sent */
		}

		final List<Sent> sent = new ArrayList<>();
		boolean accepting = true;
		boolean readable;
		boolean closed;
		int closes;

		@Override
		public boolean send(InetAddress address, int port, byte[] bytes) {
			if (accepting) {
				sent.add(new Sent(address, port, bytes));
			}

			return accepting;
		}

		@Override
		public InetSocketAddress localAddress() {
			return new InetSocketAddress(0);
		}

		@Override
		public void setReadable(boolean readable) {
			this.readable = readable;
		}

		@Override
		public void close() {
			closed = true;
			closes++;
		}
	}
}
