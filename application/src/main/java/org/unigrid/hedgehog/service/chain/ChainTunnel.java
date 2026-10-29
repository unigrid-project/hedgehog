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

import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.Close;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.Connected;
import org.unigrid.hedgehog.proxy.session.Data;
import org.unigrid.hedgehog.proxy.session.Datagram;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import org.unigrid.hedgehog.proxy.socks.AssociationListener;
import org.unigrid.hedgehog.proxy.socks.AssociationStream;
import org.unigrid.hedgehog.proxy.socks.Tunnel;
import org.unigrid.hedgehog.proxy.socks.TunnelFailure;
import org.unigrid.hedgehog.proxy.socks.TunnelListener;
import org.unigrid.hedgehog.proxy.socks.TunnelStream;

/* The tunnel of the SOCKS front end over client sessions: each stream is a session towards an exit that carries
   the destination, and each UDP association a session whose datagrams the exit relays. The spork is read for each,
   and while it is absent or does not allow CONNECT for a stream, or UDP for an association, it is refused as not
   allowed and no session is started. */
@Slf4j
@RequiredArgsConstructor
public final class ChainTunnel implements Tunnel {
	/* What a stream holds back for a client that reads slowly. A client that falls further behind is dropped,
	   because the chain has no flow control of its own to slow the exit down. */
	static final int MAX_HELD_BYTES = 1024 * 1024;

	/* The join waits up to ten seconds for each member it asks, the session five seconds for each of three exits
	   and the exit may try eight addresses of a name for ten seconds each: two minutes lets a slow path answer,
	   but one slow at every step takes about 135 seconds and ends as TTL_EXPIRED. */
	static final long DEADLINE_MILLIS = 120_000;

	private final ProxySettingsSource settings;
	private final SessionOpener sessions;
	private final ScheduledExecutorService timers;

	@Override
	public TunnelStream open(final SocksAddress destination, final Optional<String> country,
		final TunnelListener listener) {

		final ChainStream stream = new ChainStream(listener);

		start(stream, ProxySettings::connectAllowed, Socks5CommandType.CONNECT, destination, country);
		return stream;
	}

	/* The exit relays the datagrams from a socket of its own, so it needs no address from the client */
	@Override
	public AssociationStream associate(final Optional<String> country, final AssociationListener listener) {
		final ChainAssociation association = new ChainAssociation(listener);

		start(association, ProxySettings::udpAllowed, Socks5CommandType.UDP_ASSOCIATE, SocksAddress.UNSPECIFIED,
			country);
		return association;
	}

	/* What cannot start is failed, so that the front end has a reply to give */
	private void start(final TunnelSession opening, final Predicate<ProxySettings> allowed,
		final Socks5CommandType command, final SocksAddress destination, final Optional<String> country) {

		try {
			if (settings.current().filter(allowed).isPresent()) {
				opening.deadline = timers.schedule(opening::expire, DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
				opening.attach(sessions.open(new Connect(command, destination), country, opening));
			} else {
				opening.fail(TunnelFailure.NOT_ALLOWED);
			}
		} catch (RuntimeException ex) {
			log.warn("Opening a session of the tunnel failed with {}", ex.getClass().getName());
			opening.fail(TunnelFailure.GENERAL_FAILURE);
		}
	}

	/* A failure is logged by its class alone, as its message may quote what the session carries, and the stream or
	   the association goes on with its next call */
	private static void guarded(final Runnable call) {
		try {
			call.run();
		} catch (RuntimeException ex) {
			log.warn("A call out of the tunnel failed with {}", ex.getClass().getName());
		}
	}

	/* What a stream and an association share: the session that carries it, the reply of the exit with its deadline,
	   and the end. Every change is decided under the lock of the stream or the association, and the calls it makes on
	   the front end and the session are made with no such lock held, the front end's one at a time and in the order
	   they were decided, so either may call back in. */
	private abstract static class TunnelSession implements SessionListener {
		protected final OrderedDrain<Runnable> tells = new OrderedDrain<>(new ConcurrentLinkedQueue<>());
		protected volatile SessionHandle session;
		protected volatile boolean connected;
		protected volatile boolean ended;
		private final Consumer<Connected> tellOpened;
		private final Consumer<TunnelFailure> tellFailed;
		private final Runnable tellClosed;
		private volatile Future<?> deadline;
		private volatile CloseReason closing;

		TunnelSession(final Consumer<Connected> tellOpened, final Consumer<TunnelFailure> tellFailed,
			final Runnable tellClosed) {

			this.tellOpened = tellOpened;
			this.tellFailed = tellFailed;
			this.tellClosed = tellClosed;
		}

		/* What the session carries besides the reply and the end */
		abstract void carried(SealedMessage message);

		@Override
		public void received(final SealedMessage message) {
			if (message instanceof Connected reply && !reply.status().isSuccess()) {
				giveUp(TunnelFailure.of(reply.status()));
			} else if (message instanceof Connected reply) {
				answered(reply);
			} else if (message instanceof Close close) {
				finished(close.reason());
			} else {
				carried(message);
			}

			drain();
		}

		/* A second reply is not the exit's to give */
		private synchronized void answered(final Connected reply) {
			if (!connected && !ended) {
				connected = true;
				cancelDeadline();
				tells.add(() -> tellOpened.accept(reply));
			}
		}

		synchronized void finished(final CloseReason reason) {
			if (connected) {
				ended(tellClosed);
			} else {
				failed(TunnelFailure.of(reason));
			}
		}

		private void expire() {
			giveUp(TunnelFailure.TTL_EXPIRED);
			drain();
		}

		/* What no exit answered in time, or an exit refused, has its session closed here: the close of the front end
		   comes after the end and closes nothing, and an exit that refused may send no Close. What got its reply is
		   left to the idle limit of its session. */
		private void giveUp(final TunnelFailure failure) {
			if (failed(failure)) {
				closeSession(CloseReason.DONE);
			}
		}

		private void fail(final TunnelFailure failure) {
			failed(failure);
			drain();
		}

		private synchronized boolean failed(final TunnelFailure failure) {
			return !connected && ended(() -> tellFailed.accept(failure));
		}

		boolean ended(final Runnable last) {
			final boolean stopped = stop();

			if (stopped) {
				tells.add(last);
			}

			return stopped;
		}

		/* False when it had ended already */
		boolean stop() {
			final boolean open = !ended;

			ended = true;
			cancelDeadline();
			return open;
		}

		private void cancelDeadline() {
			Optional.ofNullable(deadline).ifPresent(timer -> timer.cancel(false));
		}

		void drain() {
			tells.drain(ChainTunnel::guarded);
		}

		/* The session may tell its end before open hands its handle over, and the front end may end what it carries
		   before then */
		private void attach(final SessionHandle handle) {
			session = handle;
			Optional.ofNullable(closing).ifPresent(this::closeSession);
		}

		void closeSession(final CloseReason reason) {
			closing = reason;
			Optional.ofNullable(session).ifPresent(open -> guarded(() -> open.close(reason)));
		}

		/* The front end hears nothing more, not even what was decided before and is not told yet */
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

	/* One stream: what the destination sends waits while the client reads slowly, and what the front end writes is
	   cut into messages that one entry carries */
	private static final class ChainStream extends TunnelSession implements TunnelStream {
		private final TunnelListener listener;
		private final Queue<byte[]> held = new ArrayDeque<>();
		private boolean readable = true;
		private int heldBytes;

		private ChainStream(final TunnelListener listener) {
			super(reply -> listener.connected(reply.bound()), listener::failed, listener::closed);
			this.listener = listener;
		}

		@Override
		void carried(final SealedMessage message) {
			if (message instanceof Data data && overflows(data.bytes())) {
				closeSession(CloseReason.BROKEN);
			}
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
		@Override
		synchronized void finished(final CloseReason reason) {
			if (connected) {
				release();
			}

			super.finished(reason);
		}

		/* Nothing is held once the stream ended, so nothing can be released after the last call of the listener */
		@Override
		boolean stop() {
			held.clear();
			return super.stop();
		}

		private void release() {
			held.forEach(bytes -> tells.add(() -> listener.received(bytes)));
			held.clear();
			heldBytes = 0;
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
	}

	/* One UDP association: each datagram is one sealed message, sent whole or dropped and never queued, as UDP
	   tolerates loss and a queue would only add delay */
	private static final class ChainAssociation extends TunnelSession implements AssociationStream {
		private final AssociationListener listener;

		private ChainAssociation(final AssociationListener listener) {
			super(reply -> listener.opened(), listener::failed, listener::closed);
			this.listener = listener;
		}

		/* The exit relays no datagram before it has set the association up, and the front end has no association to
		   hand one to before the reply, so a datagram that comes earlier is dropped */
		@Override
		synchronized void carried(final SealedMessage message) {
			if (message instanceof Datagram datagram && connected && !ended) {
				tells.add(() -> listener.received(datagram.address(), datagram.bytes()));
			}
		}

		@Override
		public void writabilityChanged(final boolean writable) {
			/* A datagram is dropped while the chain has no room, so nothing waits for it */
		}

		/* Sent with no lock held, as the session is never posted to under one: what ends the association meanwhile
		   leaves the session to refuse the datagram */
		@Override
		public boolean send(final SocksAddress destination, final byte[] bytes) {
			final SessionHandle open = session;

			if (open == null || !takes(open, destination, bytes)) {
				return false;
			}

			try {
				open.send(new Datagram(destination, bytes));
				return true;
			} catch (IllegalStateException ex) {
				/* The session ended under the send, and its Close tells the listener so */
				return false;
			}
		}

		/* A datagram the session would refuse as too large for one entry is dropped here, so an
		   IllegalArgumentException out of the session is a fault and is left to the caller */
		private boolean takes(final SessionHandle open, final SocksAddress destination, final byte[] bytes) {
			return connected && !ended && open.isWritable()
				&& bytes.length <= open.maxDataBytes() - destination.encodedSize();
		}
	}
}
