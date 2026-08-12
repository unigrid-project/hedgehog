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

import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.Close;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.Connected;
import org.unigrid.hedgehog.proxy.session.Data;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import org.unigrid.hedgehog.proxy.socks.Tunnel;
import org.unigrid.hedgehog.proxy.socks.TunnelFailure;
import org.unigrid.hedgehog.proxy.socks.TunnelListener;
import org.unigrid.hedgehog.proxy.socks.TunnelStream;

/* The tunnel of the SOCKS front end over client sessions: each stream is a session towards an exit that carries
   the destination. The spork is read for every stream, and while it is absent or does not allow CONNECT the stream
   is refused as not allowed and no session is started. */
@Slf4j
@RequiredArgsConstructor
public final class ChainTunnel implements Tunnel {
	/* What a stream holds back for a client that reads slowly. A client that falls further behind is dropped,
	   because the chain has no flow control of its own to slow the exit down. */
	static final int MAX_HELD_BYTES = 1024 * 1024;

	/* The join waits up to ten seconds for each member it asks, the session five seconds for each of three exits
	   and the exit may try eight addresses of a name for ten seconds each, so two minutes lets such a slow path
	   answer and still ends a stream that nothing will answer. */
	static final long DEADLINE_MILLIS = 120_000;

	private final ProxySettingsSource settings;
	private final SessionOpener sessions;
	private final ScheduledExecutorService timers;

	/* Never throws: a stream that cannot start is failed, so that the front end has a reply to give */
	@Override
	public TunnelStream open(final SocksAddress destination, final TunnelListener listener) {
		final ChainStream stream = new ChainStream(listener);

		try {
			if (settings.current().filter(ProxySettings::connectAllowed).isPresent()) {
				stream.deadline = timers.schedule(stream::expire, DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
				stream.attach(sessions.open(new Connect(Socks5CommandType.CONNECT, destination), stream));
			} else {
				stream.fail(TunnelFailure.NOT_ALLOWED);
			}
		} catch (RuntimeException ex) {
			log.warn("Opening a tunnel stream failed with {}", ex.getClass().getName());
			stream.fail(TunnelFailure.GENERAL_FAILURE);
		}

		return stream;
	}

	/* A failure is logged by its class alone, as its message may quote what the stream carries, and the stream goes
	   on with its next call */
	private static void guarded(final Runnable call) {
		try {
			call.run();
		} catch (RuntimeException ex) {
			log.warn("A call out of a tunnel stream failed with {}", ex.getClass().getName());
		}
	}

	/* One stream: what the session produces reaches the front end in order, and what the front end writes is cut into
	   messages that one entry carries. Every change is decided under the lock of the stream, and the calls it makes
	   on the listener and the session are made with no lock of the stream held, the listener's one at a time and in
	   the order they were decided, so either may call back in. */
	private static final class ChainStream implements TunnelStream, SessionListener {
		private final TunnelListener listener;
		private final Queue<byte[]> held = new ArrayDeque<>();
		private final OrderedDrain<Runnable> tells = new OrderedDrain<>(new ConcurrentLinkedQueue<>());
		private volatile Future<?> deadline;
		private volatile SessionHandle session;
		private volatile CloseReason closing;
		private volatile boolean ended;
		private boolean connected;
		private boolean readable = true;
		private int heldBytes;

		private ChainStream(final TunnelListener listener) {
			this.listener = listener;
		}

		@Override
		public void received(final SealedMessage message) {
			if (message instanceof Connected reply) {
				answered(reply);
			} else if (message instanceof Data data && overflows(data.bytes())) {
				closeSession(CloseReason.BROKEN);
			} else if (message instanceof Close close) {
				finished(close.reason());
			}

			drain();
		}

		/* The front end reads the writability of the stream when it hears the reply, so a change before then is
		   moot */
		@Override
		public void writabilityChanged(final boolean writable) {
			synchronized (this) {
				if (connected && !ended) {
					tells.add(() -> listener.writabilityChanged(writable));
				}
			}

			drain();
		}

		/* A second reply is not the exit's to give */
		private synchronized void answered(final Connected reply) {
			if (!reply.status().isSuccess()) {
				failed(TunnelFailure.of(reply.status()));
			} else if (!connected && !ended) {
				connected = true;
				cancelDeadline();
				tells.add(() -> listener.connected(reply.bound()));
			}
		}

		/* What the client cannot take yet waits in order. True when that took the stream beyond what it holds and
		   dropped it, whatever was held with it, as the client is not reading it. A message without bytes holds
		   nothing back that the bound could count, so it is not kept. */
		private synchronized boolean overflows(final byte[] bytes) {
			if (ended || !connected || bytes.length == 0) {
				return false;
			}

			if (readable) {
				tells.add(() -> listener.received(bytes));
				return false;
			}

			held.add(bytes);
			heldBytes += bytes.length;
			return heldBytes > MAX_HELD_BYTES && ended(listener::closed);
		}

		/* What the destination sent before it closed reaches the client first, held back or not, as nothing is left
		   to release it once the front end heard the end */
		private synchronized void finished(final CloseReason reason) {
			if (connected) {
				release();
				ended(listener::closed);
			} else {
				failed(TunnelFailure.of(reason));
			}
		}

		/* A stream that no exit answered in time is ended and its session closed, as the front end cuts nothing
		   after the command; one that got its reply is left to the idle limit of its session */
		private void expire() {
			if (failed(TunnelFailure.TTL_EXPIRED)) {
				closeSession(CloseReason.DONE);
			}

			drain();
		}

		private void fail(final TunnelFailure failure) {
			failed(failure);
			drain();
		}

		private synchronized boolean failed(final TunnelFailure failure) {
			return !connected && ended(() -> listener.failed(failure));
		}

		private boolean ended(final Runnable last) {
			final boolean stopped = stop();

			if (stopped) {
				tells.add(last);
			}

			return stopped;
		}

		/* Nothing is held once the stream ended, so nothing can be released after the last call of the listener.
		   False when the stream had ended already. */
		private boolean stop() {
			final boolean open = !ended;

			ended = true;
			held.clear();
			cancelDeadline();
			return open;
		}

		private void cancelDeadline() {
			Optional.ofNullable(deadline).ifPresent(timer -> timer.cancel(false));
		}

		private void release() {
			held.forEach(bytes -> tells.add(() -> listener.received(bytes)));
			held.clear();
			heldBytes = 0;
		}

		private void drain() {
			tells.drain(ChainTunnel::guarded);
		}

		/* The session may tell its end before open hands its handle over, and the stream may end it before then */
		private void attach(final SessionHandle handle) {
			session = handle;
			Optional.ofNullable(closing).ifPresent(this::closeSession);
		}

		private void closeSession(final CloseReason reason) {
			closing = reason;
			Optional.ofNullable(session).ifPresent(open -> guarded(() -> open.close(reason)));
		}

		@Override
		public void write(final byte[] bytes) {
			final SessionHandle open = session;
			final int piece = ended || open == null ? 0 : open.maxDataBytes();

			try {
				for (int from = 0; piece > 0 && from < bytes.length; from += piece) {
					final int to = Math.min(bytes.length, from + piece);

					open.send(new Data(Arrays.copyOfRange(bytes, from, to)));
				}
			} catch (IllegalStateException ex) {
				/* The session ended under the write, and its Close tells the listener so */
			}
		}

		@Override
		public boolean isWritable() {
			final SessionHandle open = session;

			return open != null && open.isWritable();
		}

		@Override
		public void setReadable(final boolean readable) {
			synchronized (this) {
				this.readable = readable;

				if (readable) {
					release();
				}
			}

			drain();
		}

		/* The listener hears nothing more, not even what was decided before and is not told yet */
		@Override
		public void close() {
			final boolean open;

			synchronized (this) {
				tells.clear();
				open = stop();
			}

			if (open) {
				closeSession(CloseReason.DONE);
			}
		}
	}
}
