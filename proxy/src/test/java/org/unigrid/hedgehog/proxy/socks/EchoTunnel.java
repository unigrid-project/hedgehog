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

package org.unigrid.hedgehog.proxy.socks;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.unigrid.hedgehog.proxy.session.Datagram;
import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* A tunnel whose every stream leads to an echo in memory: what the front end writes comes back, except while
   the front end holds delivery back. The test decides when a stream connects, fails, ends, stops taking
   writes or breaks, and every call reaches the listener on the test's own thread. */
final class EchoTunnel implements Tunnel {
	final List<EchoStream> streams = new ArrayList<>();
	final List<EchoAssociation> associations = new ArrayList<>();

	@Override
	public TunnelStream open(SocksAddress destination, TunnelListener listener) {
		final EchoStream stream = new EchoStream(destination, listener);

		streams.add(stream);
		return stream;
	}

	/* The stream opened last */
	EchoStream stream() {
		return streams.get(streams.size() - 1);
	}

	@Override
	public AssociationStream associate(AssociationListener listener) {
		final EchoAssociation association = new EchoAssociation(listener);

		associations.add(association);
		return association;
	}

	/* The association opened last */
	EchoAssociation association() {
		return associations.get(associations.size() - 1);
	}

	/* Keeps the datagrams the front end sends; the test decides when the association opens, fails, ends or breaks,
	   and what comes back from the exit. It notes a call made while the front end held its listener's lock. */
	static final class EchoAssociation implements AssociationStream {
		final AssociationListener listener;
		final List<Datagram> sent = new ArrayList<>();
		boolean accepting = true;
		boolean closed;
		boolean broken;
		boolean closeBroken;
		boolean calledUnderLock;

		EchoAssociation(AssociationListener listener) {
			this.listener = listener;
		}

		void open() {
			listener.opened();
		}

		void fail(TunnelFailure failure) {
			listener.failed(failure);
		}

		void deliver(SocksAddress source, byte[] bytes) {
			listener.received(source, bytes);
		}

		void finish() {
			listener.closed();
		}

		@Override
		public boolean send(SocksAddress destination, byte[] bytes) {
			calledUnderLock |= Thread.holdsLock(listener);

			if (broken) {
				throw new IllegalStateException("The association is broken");
			}

			if (accepting) {
				sent.add(new Datagram(destination, bytes));
			}

			return accepting;
		}

		@Override
		public void close() {
			calledUnderLock |= Thread.holdsLock(listener);
			closed = true;

			if (closeBroken) {
				throw new IllegalStateException("The association cannot be closed");
			}
		}
	}

	static final class EchoStream implements TunnelStream {
		final SocksAddress destination;
		final TunnelListener listener;
		final ByteArrayOutputStream written = new ByteArrayOutputStream();
		final Deque<byte[]> echoes = new ArrayDeque<>();
		boolean readable = true;
		boolean writable = true;
		boolean closed;
		boolean broken;

		EchoStream(SocksAddress destination, TunnelListener listener) {
			this.destination = destination;
			this.listener = listener;
		}

		void connect(SocksAddress bound) {
			listener.connected(bound);
		}

		void fail(TunnelFailure failure) {
			listener.failed(failure);
		}

		/* The far end closes */
		void finish() {
			listener.closed();
		}

		void takeWrites(boolean writable) {
			this.writable = writable;
			listener.writabilityChanged(writable);
		}

		@Override
		public void write(byte[] bytes) {
			if (broken) {
				throw new IllegalStateException("The stream is broken");
			}

			written.writeBytes(bytes);
			echoes.add(bytes);
			echo();
		}

		@Override
		public boolean isWritable() {
			return writable;
		}

		@Override
		public void setReadable(boolean readable) {
			this.readable = readable;
			echo();
		}

		@Override
		public void close() {
			closed = true;
		}

		private void echo() {
			while (readable && !echoes.isEmpty()) {
				listener.received(echoes.poll());
			}
		}
	}
}
