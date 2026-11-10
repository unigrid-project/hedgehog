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

package org.unigrid.hedgehog.model.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;

/*
   A host on the loopback interface that answers with exactly what a test hands it, the way a release
   server does, and counts every transfer whose reader went away before the body was written to the
   end. A body can therefore be as long as a hostile server would make it, and the count tells whether
   the node closed the transfer or left it open.
*/
final class LoopbackHost implements AutoCloseable {
	private static final byte[] FOUND = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n"
		.getBytes(StandardCharsets.US_ASCII);
	private static final byte[] ABSENT = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
		.getBytes(StandardCharsets.US_ASCII);
	private static final int CHUNK = 1 << 16;

	@FunctionalInterface
	interface Body {
		void writeTo(OutputStream out) throws IOException;
	}

	private final ServerSocket socket;
	private final Map<String, Body> bodies = new ConcurrentHashMap<>();
	private final AtomicInteger abandoned = new AtomicInteger();

	@SneakyThrows
	LoopbackHost() {
		socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
		Thread.ofPlatform().daemon().start(this::accept);
	}

	/* Bytes of one value, as many as asked for, written in chunks so the length may exceed any buffer. */
	static Body filled(byte value, long length) {
		return out -> {
			final byte[] chunk = new byte[CHUNK];
			Arrays.fill(chunk, value);

			for (long left = length; left > 0; left -= CHUNK) {
				out.write(chunk, 0, (int) Math.min(left, CHUNK));
			}
		};
	}

	void serve(String path, byte[] contents) {
		serve(path, out -> out.write(contents));
	}

	void serve(String path, Body body) {
		bodies.put(path, body);
	}

	@SneakyThrows
	URL url(String path) {
		return URI.create("http://127.0.0.1:" + socket.getLocalPort() + path).toURL();
	}

	/* The transfers whose reader closed or reset them while the body was still being written. */
	int abandoned() {
		return abandoned.get();
	}

	@Override
	@SneakyThrows
	public void close() {
		socket.close();
	}

	private void accept() {
		try {
			while (!socket.isClosed()) {
				final Socket client = socket.accept();

				Thread.ofPlatform().daemon().start(() -> answer(client));
			}
		} catch (IOException ex) {
			/* The socket was closed, which is how the host stops */
		}
	}

	private void answer(Socket client) {
		try (client) {
			final Body body = bodies.get(requestedPath(client));
			final OutputStream out = client.getOutputStream();

			if (body == null) {
				out.write(ABSENT);
				return;
			}

			out.write(FOUND);
			write(body, out);

		} catch (IOException ex) {
			/* A request that never arrived whole is not a reader leaving a body unfinished */
		}
	}

	private void write(Body body, OutputStream out) {
		try {
			body.writeTo(out);
			out.flush();
		} catch (IOException ex) {
			abandoned.incrementAndGet();
		}
	}

	private static String requestedPath(Socket client) throws IOException {
		final BufferedReader request = new BufferedReader(new InputStreamReader(client.getInputStream(),
			StandardCharsets.US_ASCII));
		final String line = request.readLine();
		final String path = line == null ? "" : line.split(" ")[1];

		for (String header = request.readLine(); header != null && !header.isEmpty(); header = request.readLine()) {
			/* The headers say nothing this host needs */
		}

		return path;
	}
}
