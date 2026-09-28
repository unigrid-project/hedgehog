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

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.unigrid.hedgehog.proxy.exit.ExitConnector;
import org.unigrid.hedgehog.proxy.exit.Target;
import org.unigrid.hedgehog.proxy.exit.TargetListener;

/* An exit connector that connects to nothing: it records what the exit asked for and how often it cancelled, and a
   test lets the connection happen by calling `connect` on a request */
final class FakeConnector implements ExitConnector {
	record Request(List<InetAddress> addresses, int port, TargetListener listener, AtomicInteger cancels) {
		/* One connection the exit asked for */
	}

	static final class FakeTarget implements Target {
		final ByteArrayOutputStream written = new ByteArrayOutputStream();
		final AtomicInteger closes = new AtomicInteger();
		volatile boolean readable;
		volatile boolean closed;
		volatile boolean failingWrites;
		volatile boolean failingClose;
		volatile Error closeError;
		volatile Runnable closing = () -> { };

		@Override
		public InetSocketAddress localAddress() {
			return new InetSocketAddress("127.0.0.1", 40_000);
		}

		@Override
		public void write(final byte[] bytes) {
			if (failingWrites) {
				throw new IllegalStateException("The destination failed");
			}

			written.writeBytes(bytes);
		}

		@Override
		public long backlogBytes() {
			return 0;
		}

		@Override
		public void setReadable(final boolean readable) {
			this.readable = readable;
		}

		@Override
		public void close() {
			closing.run();
			closed = true;
			closes.incrementAndGet();

			if (failingClose) {
				throw new IllegalStateException("The destination failed to close");
			}

			if (closeError != null) {
				throw closeError;
			}
		}
	}

	final List<Request> requests = new CopyOnWriteArrayList<>();

	@Override
	public Attempt connect(final List<InetAddress> addresses, final int port, final TargetListener listener) {
		final Request request = new Request(addresses, port, listener, new AtomicInteger());

		requests.add(request);
		return request.cancels()::incrementAndGet;
	}

	Request last() {
		return requests.get(requests.size() - 1);
	}

	/* The connection of the last request goes through */
	FakeTarget connect() {
		return connect(requests.size() - 1);
	}

	FakeTarget connect(final int request) {
		final FakeTarget target = new FakeTarget();

		requests.get(request).listener().connected(target);
		return target;
	}
}
