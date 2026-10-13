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

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/* A loopback listener whose accept queue is full, so the kernel drops every new connection attempt and a connect to
   it hangs until it times out or is closed. Once the queue has room again, an attempt still under way gets in with
   the SYN it sends a second after its first. */
final class Blackhole implements AutoCloseable {
	private static final int MAX_QUEUED = 16;
	private static final int FILL_TIMEOUT_MILLIS = 300;

	private final ServerSocket server;
	private final List<Socket> queued = new ArrayList<>();

	Blackhole() throws IOException {
		this(EchoServer.LOOPBACK, 0);
	}

	Blackhole(InetAddress address, int port) throws IOException {
		server = new ServerSocket(port, 1, address);

		while (queued.size() < MAX_QUEUED) {
			final Socket socket = new Socket();

			try {
				socket.connect(server.getLocalSocketAddress(), FILL_TIMEOUT_MILLIS);
				queued.add(socket);
			} catch (SocketTimeoutException full) {
				socket.close();
				return;
			}
		}

		throw new AssertionError("The accept queue still takes connections after " + MAX_QUEUED);
	}

	int port() {
		return server.getLocalPort();
	}

	/* Accepts what filled the queue, which makes room, then waits up to the given time for one more connection */
	boolean takesAnotherWithin(int millis) throws IOException {
		server.setSoTimeout(millis);

		for (int i = 0; i < queued.size(); i++) {
			server.accept().close();
		}

		try (Socket late = server.accept()) {
			return true;
		} catch (SocketTimeoutException none) {
			return false;
		}
	}

	@Override
	public void close() throws IOException {
		server.close();

		for (Socket socket : queued) {
			socket.close();
		}
	}
}
