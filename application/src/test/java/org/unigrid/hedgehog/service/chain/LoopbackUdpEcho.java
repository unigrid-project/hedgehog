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

package org.unigrid.hedgehog.service.chain;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/* A UDP server on 127.0.0.1 that sends every datagram back to where it came from. It counts the datagrams before it
   answers them and notes each address they came from. */
final class LoopbackUdpEcho implements AutoCloseable {
	private static final int LARGEST_DATAGRAM = 65_535;
	private static final int JOIN_MILLIS = 5_000;

	final AtomicInteger received = new AtomicInteger();
	private final CopyOnWriteArrayList<InetSocketAddress> peers = new CopyOnWriteArrayList<>();
	private final DatagramSocket socket;
	private final Thread thread;

	LoopbackUdpEcho() throws IOException {
		socket = new DatagramSocket(0, loopback());
		thread = new Thread(this::echo, "loopback-udp-echo");
		thread.setDaemon(true);
		thread.start();
	}

	static InetAddress loopback() throws IOException {
		return InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
	}

	/* A port that nothing reads, as one that was just let go */
	static int closedPort() throws IOException {
		try (DatagramSocket gone = new DatagramSocket(0, loopback())) {
			return gone.getLocalPort();
		}
	}

	/* Whether a socket could bind to the address now, which it cannot while another socket holds the port. A
	   specific address collides with a wildcard bind of the same port on Linux and BSD, not on Windows. */
	static boolean isFree(final InetSocketAddress address) throws IOException {
		try (DatagramSocket probe = new DatagramSocket(address)) {
			return true;
		} catch (BindException held) {
			return false;
		}
	}

	InetSocketAddress address() {
		return new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
	}

	/* The addresses the datagrams came from, each once, in the order they first came */
	List<InetSocketAddress> peers() {
		return List.copyOf(peers);
	}

	@Override
	public void close() throws InterruptedException {
		socket.close();
		thread.join(JOIN_MILLIS);
	}

	private void echo() {
		final byte[] buffer = new byte[LARGEST_DATAGRAM];

		try {
			while (!socket.isClosed()) {
				final DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);

				socket.receive(datagram);
				peers.addIfAbsent((InetSocketAddress) datagram.getSocketAddress());
				received.incrementAndGet();
				socket.send(new DatagramPacket(buffer, datagram.getLength(), datagram.getSocketAddress()));
			}
		} catch (IOException closed) {
			/* The server was closed, which is how a test ends it */
		}
	}
}
