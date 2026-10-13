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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/* A TCP echo on one loopback address, 127.0.0.1 unless told otherwise: every connection gets back what it sends
   until it closes */
final class EchoServer implements AutoCloseable {
	static final InetAddress LOOPBACK = loopback(1);

	private final ServerSocket server;
	private final List<Socket> accepted = new CopyOnWriteArrayList<>();
	private final AtomicLong echoed = new AtomicLong();

	EchoServer() throws IOException {
		this(LOOPBACK);
	}

	EchoServer(InetAddress address) throws IOException {
		server = new ServerSocket(0, 50, address);
		daemon(this::accept);
	}

	/* 127.0.0.n, built from its bytes so nothing is ever resolved */
	static InetAddress loopback(int last) {
		try {
			return InetAddress.getByAddress(new byte[] { 127, 0, 0, (byte) last });
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	int port() {
		return server.getLocalPort();
	}

	/* The port the first client connected from, once it has */
	int peerPort() throws InterruptedException {
		RecordingTargetListener.await(() -> !accepted.isEmpty());
		return accepted.get(0).getPort();
	}

	/* Accepted connections that are still open */
	long open() {
		return accepted.stream().filter(socket -> !socket.isClosed()).count();
	}

	/* Bytes sent back so far, over all connections */
	long echoed() {
		return echoed.get();
	}

	@Override
	public void close() throws IOException {
		server.close();

		for (Socket socket : accepted) {
			socket.close();
		}
	}

	private void accept() {
		try {
			while (true) {
				final Socket socket = server.accept();

				accepted.add(socket);
				daemon(() -> echo(socket));
			}
		} catch (IOException closed) {
			/* The server was closed */
		}
	}

	private void echo(Socket socket) {
		final byte[] buffer = new byte[8192];

		try (socket) {
			final InputStream in = socket.getInputStream();
			final OutputStream out = socket.getOutputStream();

			for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
				out.write(buffer, 0, read);
				echoed.addAndGet(read);
			}
		} catch (IOException closed) {
			/* Either side closed the connection */
		}
	}

	private static void daemon(Runnable work) {
		final Thread thread = new Thread(work, "echo");

		thread.setDaemon(true);
		thread.start();
	}
}
