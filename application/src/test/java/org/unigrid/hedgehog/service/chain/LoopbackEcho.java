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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/* A TCP server on 127.0.0.1 that sends back what it receives, up to a number of bytes after which it hangs up. It
   notes where each client connected from, how much it received and when one has gone, and a test may have it hold
   back what it would send for a while. */
final class LoopbackEcho implements AutoCloseable {
	private static final int BACKLOG = 50;
	private static final int BUFFER = 8192;
	private static final int JOIN_MILLIS = 10_000;

	final List<InetSocketAddress> peers = new CopyOnWriteArrayList<>();
	final CountDownLatch disconnected = new CountDownLatch(1);
	final AtomicLong received = new AtomicLong();
	private final List<Socket> clients = new CopyOnWriteArrayList<>();
	private final List<Thread> threads = new CopyOnWriteArrayList<>();
	private final ServerSocket server;
	private final long hangsUpAfter;

	/* What arrived while the echo was held, by client; guarded by the echo's monitor */
	private final Map<Socket, ByteArrayOutputStream> heldBack = new HashMap<>();
	private boolean holding;

	LoopbackEcho() throws IOException {
		this(Long.MAX_VALUE);
	}

	LoopbackEcho(final long hangsUpAfter) throws IOException {
		this.hangsUpAfter = hangsUpAfter;
		server = new ServerSocket(0, BACKLOG, loopback());
		start(this::acceptLoop);
	}

	/* The address the destinations are named by, whatever the JVM prefers for the loopback name */
	private static InetAddress loopback() throws IOException {
		return InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
	}

	/* A port that nothing listens on, as one that was just let go */
	static int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, loopback())) {
			return socket.getLocalPort();
		}
	}

	int port() {
		return server.getLocalPort();
	}

	/* What arrives from now on is kept back until the echo is let go */
	synchronized void hold() {
		holding = true;
	}

	/* Sends what was kept back, and echoes at once again */
	synchronized void release() throws IOException {
		holding = false;

		for (final Map.Entry<Socket, ByteArrayOutputStream> held : heldBack.entrySet()) {
			held.getKey().getOutputStream().write(held.getValue().toByteArray());
		}

		heldBack.clear();
	}

	/* The clients are hung up on too, as their threads would otherwise wait for them */
	@Override
	public void close() throws IOException, InterruptedException {
		server.close();

		for (final Socket client : clients) {
			client.close();
		}

		for (final Thread thread : threads) {
			thread.join(JOIN_MILLIS);
		}
	}

	private void start(final Runnable body) {
		final Thread thread = new Thread(body, "loopback-echo");

		thread.setDaemon(true);
		threads.add(thread);
		thread.start();
	}

	private void acceptLoop() {
		try {
			while (!server.isClosed()) {
				final Socket client = server.accept();

				clients.add(client);
				peers.add((InetSocketAddress) client.getRemoteSocketAddress());
				start(() -> echo(client));
			}
		} catch (IOException ex) {
			/* The server was closed, which is how a test ends it */
		}
	}

	/* Reads no more than it has left to send, so that it hangs up right after the last byte it sends back */
	private void echo(final Socket client) {
		final byte[] buffer = new byte[BUFFER];
		long left = hangsUpAfter;

		try (client) {
			final InputStream in = client.getInputStream();
			final OutputStream out = client.getOutputStream();

			for (int read = in.read(buffer, 0, room(left)); read > 0; read = in.read(buffer, 0, room(left))) {
				send(client, out, buffer, read);
				received.addAndGet(read);
				left -= read;
			}
		} catch (IOException ex) {
			/* The client went away, which is what the latch is told */
		} finally {
			disconnected.countDown();
		}
	}

	private synchronized void send(final Socket client, final OutputStream out, final byte[] buffer, final int read)
		throws IOException {

		if (holding) {
			heldBack.computeIfAbsent(client, held -> new ByteArrayOutputStream()).write(buffer, 0, read);
		} else {
			out.write(buffer, 0, read);
		}
	}

	private static int room(final long left) {
		return (int) Math.min(BUFFER, left);
	}
}
