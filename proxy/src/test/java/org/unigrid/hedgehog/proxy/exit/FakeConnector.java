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

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/* Records what the relay asks to connect to; the test answers through the request's listener */
final class FakeConnector implements ExitConnector {
	final List<Request> requests = new ArrayList<>();

	record Request(List<InetAddress> addresses, int port, TargetListener listener) {
		/* One call of connect */
	}

	@Override
	public void connect(List<InetAddress> addresses, int port, TargetListener listener) {
		requests.add(new Request(addresses, port, listener));
	}

	Request last() {
		return requests.get(requests.size() - 1);
	}

	/* A destination connection held in memory */
	static class FakeTarget implements Target {
		final InetSocketAddress local;
		final ByteArrayOutputStream written = new ByteArrayOutputStream();
		long backlog;
		boolean readable;
		boolean closed;

		FakeTarget(InetSocketAddress local) {
			this.local = local;
		}

		@Override
		public InetSocketAddress localAddress() {
			return local;
		}

		@Override
		public void write(byte[] bytes) {
			written.writeBytes(bytes);
		}

		@Override
		public long backlogBytes() {
			return backlog;
		}

		@Override
		public void setReadable(boolean readable) {
			this.readable = readable;
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
