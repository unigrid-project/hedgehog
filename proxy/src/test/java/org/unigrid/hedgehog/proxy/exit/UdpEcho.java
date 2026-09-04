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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/* A UDP echo on 127.0.0.1 and nowhere else: every datagram is sent back to where it came from */
final class UdpEcho implements AutoCloseable {
	private static final int LARGEST_DATAGRAM = 65_535;

	private final DatagramSocket socket;

	UdpEcho() throws IOException {
		socket = new DatagramSocket(0, EchoServer.LOOPBACK);

		final Thread thread = new Thread(this::echo, "udp-echo");

		thread.setDaemon(true);
		thread.start();
	}

	InetAddress address() {
		return socket.getLocalAddress();
	}

	int port() {
		return socket.getLocalPort();
	}

	@Override
	public void close() {
		socket.close();
	}

	private void echo() {
		final byte[] buffer = new byte[LARGEST_DATAGRAM];

		try {
			while (!socket.isClosed()) {
				final DatagramPacket received = new DatagramPacket(buffer, buffer.length);

				socket.receive(received);
				socket.send(new DatagramPacket(buffer, received.getLength(), received.getSocketAddress()));
			}
		} catch (IOException closed) {
			/* The echo was closed */
		}
	}
}
