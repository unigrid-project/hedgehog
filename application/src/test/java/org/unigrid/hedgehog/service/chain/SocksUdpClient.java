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

package org.unigrid.hedgehog.service.chain;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/* A SOCKS5 client for UDP written by hand from RFC 1928, so that the codecs of the proxy are not both ends of the
   test: the greeting and UDP ASSOCIATE on a TCP control connection, then datagrams behind the UDP header to the
   address the proxy answered with. IPv4 only, as everything it talks to is on 127.0.0.1. Every read waits five
   seconds at most. */
final class SocksUdpClient implements AutoCloseable {
	static final int WAIT_MILLIS = 10_000;
	private static final byte VERSION = 5;
	private static final byte NO_AUTHENTICATION = 0;
	private static final byte UDP_ASSOCIATE = 3;
	private static final byte IPV4 = 1;
	private static final int IPV4_BYTES = 4;
	private static final int ADDRESS_AT = 4;
	private static final int IPV4_HEADER = ADDRESS_AT + IPV4_BYTES + Short.BYTES;
	private static final int LARGEST_DATAGRAM = 65_535;

	record Reply(InetSocketAddress source, byte[] data) {
		/* A datagram the proxy relayed, by the address its header names */
	}

	private final Socket control;
	private final DatagramSocket socket;
	private InetSocketAddress relay;

	SocksUdpClient(final int socksPort) throws IOException {
		control = new Socket(LoopbackUdpEcho.loopback(), socksPort);
		control.setSoTimeout(WAIT_MILLIS);
		socket = new DatagramSocket(0, LoopbackUdpEcho.loopback());
		socket.setSoTimeout(WAIT_MILLIS);
	}

	/* RSV RSV FRAG, the destination as ATYP, address and port, then the data */
	static byte[] packet(final InetSocketAddress destination, final byte[] data, final int fragment) {
		return ByteBuffer.allocate(IPV4_HEADER + data.length).put(new byte[] { 0, 0, (byte) fragment, IPV4 })
			.put(destination.getAddress().getAddress()).putShort((short) destination.getPort()).put(data).array();
	}

	/* The port the datagrams of this client come from */
	int port() {
		return socket.getLocalPort();
	}

	/* The address of the last reply to UDP ASSOCIATE: the proxy's datagram socket, or nothing for a refusal */
	InetSocketAddress relay() {
		return relay;
	}

	/* Greets without authentication and asks for UDP ASSOCIATE from the unspecified address and the port the
	   datagrams will come from, 0 for any. Answers the reply code. */
	int associate(final int port) throws IOException {
		final DataInputStream in = new DataInputStream(control.getInputStream());
		final byte[] method = new byte[2];
		final byte[] reply = new byte[IPV4_HEADER];

		control.getOutputStream().write(new byte[] { VERSION, 1, NO_AUTHENTICATION });
		in.readFully(method);
		assertThat("The method the proxy chose", method, equalTo(new byte[] { VERSION, NO_AUTHENTICATION }));

		control.getOutputStream().write(ByteBuffer.allocate(IPV4_HEADER).put(new byte[] { VERSION, UDP_ASSOCIATE, 0,
			IPV4 }).put(new byte[IPV4_BYTES]).putShort((short) port).array());
		in.readFully(reply);
		assertThat("VER, RSV and ATYP of the reply", new byte[] { reply[0], reply[2], reply[3] },
			equalTo(new byte[] { VERSION, 0, IPV4 }));

		relay = ipv4(reply);
		return reply[1];
	}

	void send(final InetSocketAddress destination, final byte[] data, final int fragment) throws IOException {
		final byte[] packet = packet(destination, data, fragment);

		socket.send(new DatagramPacket(packet, packet.length, relay));
	}

	/* The next datagram, which must come from the proxy's socket with a whole header and no fragment */
	Reply receive() throws IOException {
		final DatagramPacket datagram = new DatagramPacket(new byte[LARGEST_DATAGRAM], LARGEST_DATAGRAM);

		socket.receive(datagram);

		final byte[] packet = Arrays.copyOf(datagram.getData(), datagram.getLength());

		assertThat("The sender", datagram.getSocketAddress(), equalTo(relay));
		assertThat("RSV RSV FRAG ATYP", Arrays.copyOf(packet, ADDRESS_AT), equalTo(new byte[] { 0, 0, 0, IPV4 }));
		return new Reply(ipv4(packet), Arrays.copyOfRange(packet, IPV4_HEADER, packet.length));
	}

	/* Whether the proxy closed the control connection within the wait */
	boolean controlClosed() throws IOException {
		try {
			return control.getInputStream().read() == -1;
		} catch (SocketTimeoutException open) {
			return false;
		}
	}

	void closeControl() throws IOException {
		control.close();
	}

	@Override
	public void close() throws IOException {
		try (socket) {
			control.close();
		}
	}

	/* The IPv4 address and port that follow ATYP, in a reply of the control connection or a UDP header alike */
	private static InetSocketAddress ipv4(final byte[] bytes) throws IOException {
		final InetAddress address = InetAddress.getByAddress(Arrays.copyOfRange(bytes, ADDRESS_AT,
			ADDRESS_AT + IPV4_BYTES));

		return new InetSocketAddress(address, Short.toUnsignedInt(ByteBuffer.wrap(bytes)
			.getShort(ADDRESS_AT + IPV4_BYTES)));
	}
}
