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

import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/* Opens the exit's datagram socket for one association */
@FunctionalInterface
public interface UdpConnector {
	/* Never waits for the socket: the listener hears opened or failed once, maybe before open returns, then
	   accepts and received until closed, from any thread */
	void open(Listener listener);

	/* A connector for a node that relays no datagrams */
	static UdpConnector unsupported() {
		return listener -> listener.failed(Socks5CommandStatus.COMMAND_UNSUPPORTED);
	}

	/* The exit's datagram socket. Reads start off: the relay turns them on when it is ready for what arrives. None
	   of these waits for the socket, as the relay calls them under its lock and the socket's thread may need it. */
	interface Socket {
		/* Sends to the address as given, never to a name. It neither blocks nor throws; false means the socket was
		   closed or its outbound buffer was full and the datagram was dropped. */
		boolean send(InetAddress address, int port, byte[] bytes);

		InetSocketAddress localAddress();

		void setReadable(boolean readable);

		void close();
	}

	interface Listener {
		void opened(Socket socket);

		void failed(Socks5CommandStatus status);

		/* Asked before the datagram's content is read, since anyone can send to the socket: a source the listener
		   refuses costs no copy */
		boolean accepts(InetSocketAddress source);

		/* A datagram from a source the listener accepted, whole */
		void received(InetSocketAddress source, byte[] bytes);

		/* The socket closed; nothing follows */
		void closed();
	}
}
