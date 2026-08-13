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

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Gap;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.ClientSession;
import org.unigrid.hedgehog.proxy.session.Close;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.SealedCodec;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.SessionCipher;
import org.unigrid.hedgehog.proxy.session.SessionContext;

/* Runs the client sessions of this node over the chains it joined: opens each on the current attachment, hands
   it the entries the host takes in, runs the accept timeout, the retry and the idle check once a second, and
   closes the sessions of a gap that the chain gave up on. A chain of an earlier epoch is left once no session
   runs on it.

   A handle holds its own lock while it calls its session, which takes the writer's lock and the outbox's. The map
   of attachments is locked for an instant only, never around a call into the client, the host or a handle, and no
   listener is called with a lock of this class held, as a listener may call back into its handle. */
@Slf4j
public final class ClientSessions implements SessionOpener {
	private static final long TICK_SECONDS = 1;

	private record Attached(SessionContext context, ChainOutbox outbox, List<Handle> handles) {
		/* The sessions on one attachment and what they share */
	}

	private final ChainClient client;
	private final ChainHost host;
	private final ProxySettingsSource settings;
	private final LongSupplier clock;
	private final SecureRandom random;
	private final ScheduledExecutorService scheduler;
	private final AtomicBoolean ticking = new AtomicBoolean();
	private final Map<ChainAttachment, Attached> attached = new ConcurrentHashMap<>();

	public ClientSessions(final ChainClient client, final ChainHost host, final ProxySettingsSource settings,
		final LongSupplier clock, final SecureRandom random, final ScheduledExecutorService scheduler) {

		this.client = client;
		this.host = host;
		this.settings = settings;
		this.clock = clock;
		this.random = random;
		this.scheduler = scheduler;
		host.addEntryListener((chainId, entry) -> each(chainId, handle -> handle.receive(entry)));
		host.addGapListener((chainId, gap) -> each(chainId, handle -> handle.lost(gap)));
	}

	/* Nothing would time out a session once the scheduler is gone, so none is started then */
	@Override
	public SessionHandle open(final Connect connect, final SessionListener listener) {
		final Handle handle = new Handle(connect, listener);

		if (scheduler.isShutdown()) {
			handle.end(CloseReason.UNREACHABLE);
			return handle;
		}

		if (ticking.compareAndSet(false, true)) {
			scheduler.scheduleWithFixedDelay(() -> guarded(this::tick), TICK_SECONDS, TICK_SECONDS,
				TimeUnit.SECONDS);
		}

		client.attachment().whenComplete((attachment, error) -> joined(handle, attachment, error));
		return handle;
	}

	/* Run about once a second, on one thread. Every attachment is asked whether it is still the one in use, sessions
	   on it or not, as that is also where the node notices the proxy turning off and leaves the chain. */
	public void tick() {
		attached.forEach((attachment, chain) -> {
			final boolean current = client.isCurrent(attachment);

			chain.outbox().tick();
			chain.handles().forEach(handle -> guarded(handle::tick));
			chain.handles().removeIf(handle -> handle.ended);

			if (!current && dropped(attachment)) {
				client.release(attachment);
			}
		});
	}

	/* Taken out of the map under the same lock that enrolls a session, so that a session enrolled meanwhile keeps
	   it */
	private boolean dropped(final ChainAttachment attachment) {
		return attached.computeIfPresent(attachment, (key, chain) -> chain.handles().isEmpty() ? null
			: chain) == null;
	}

	/* A failure is logged by its class alone, as a message may quote what a session holds, and the others go on:
	   an exception that escaped the tick would cancel its schedule for good */
	private static void guarded(final Runnable action) {
		try {
			action.run();
		} catch (RuntimeException ex) {
			log.warn("A client session failed with {}", ex.getClass().getName());
		}
	}

	/* Any failure on the way leaves the session unreachable, as the listener would otherwise wait for good. An
	   attachment that no session runs on is let go at once while the proxy is off. Otherwise it is enrolled all the
	   same, even for a handle closed meanwhile, and the tick lets it go once it is no longer the one in use, as the
	   sessions opened together with this one may be about to use it. */
	private void joined(final Handle handle, final ChainAttachment attachment, final Throwable error) {
		try {
			final Optional<ProxySettings> proxy = error == null ? settings.current() : Optional.empty();

			if (proxy.isPresent()) {
				handle.start(attachment, proxy.get());
				return;
			}
		} catch (RuntimeException ex) {
			log.warn("Starting a client session failed with {}", ex.getClass().getName());
		}

		handle.end(CloseReason.UNREACHABLE);

		if (attachment != null && !attached.containsKey(attachment)) {
			client.release(attachment);
		}
	}

	/* Every session of the chain gets what the host took in or gave up on, and takes what is its own */
	private void each(final ChainId chainId, final Consumer<Handle> call) {
		attached.forEach((attachment, chain) -> {
			if (attachment.chainId().equals(chainId)) {
				chain.handles().forEach(handle -> guarded(() -> call.accept(handle)));
			}
		});
	}

	/* The sessions of an attachment share the settings that were current when it was first used */
	private Attached enroll(final ChainAttachment attachment, final ProxySettings proxy, final Handle handle) {
		return attached.compute(attachment, (key, existing) -> {
			final Attached chain = existing == null ? attach(key, proxy) : existing;

			if (!handle.ended) {
				chain.handles().add(handle);
			}

			return chain;
		});
	}

	private Attached attach(final ChainAttachment attachment, final ProxySettings proxy) {
		final List<Handle> handles = new CopyOnWriteArrayList<>();
		final ChainOutbox outbox = new ChainOutbox(host, attachment.chainId(), attachment.writerId(),
			writable -> handles.forEach(handle -> handle.writabilityChanged(writable)));

		return new Attached(new SessionContext(attachment.writer(), outbox, clock, random, proxy), outbox, handles);
	}

	private final class Handle implements SessionHandle {
		private final Connect connect;
		private final SessionListener listener;
		private final OrderedDrain<SealedMessage> untold = new OrderedDrain<>(new ConcurrentLinkedQueue<>());
		private volatile Attached chain;
		private volatile boolean ended;
		private ClientSession session;

		private Handle(final Connect connect, final SessionListener listener) {
			this.connect = connect;
			this.listener = listener;
		}

		/* Nothing is started for an opener that closed meanwhile, and nothing is left to try when no exit fits */
		private void start(final ChainAttachment attachment, final ProxySettings proxy) {
			ask(() -> {
				final Attached joined = enroll(attachment, proxy, this);

				if (ended) {
					return Optional.empty();
				}

				chain = joined;
				session = new ClientSession(joined.context(), attachment::exit, connect);
				return session.open() ? Optional.empty() : Optional.of(new Close(CloseReason.UNREACHABLE));
			});
		}

		private void end(final CloseReason reason) {
			ask(() -> Optional.of(new Close(reason)));
		}

		private void receive(final Entry entry) {
			ask(() -> session.receive(entry));
		}

		private void tick() {
			ask(() -> session.tick());
		}

		/* Any writer can leave a gap that names any fingerprint, so only the gap of the exit's own feed counts */
		private void lost(final Gap gap) {
			ask(() -> session.exitWriter().filter(gap.writer()::equals).flatMap(exit -> session.fingerprint())
				.filter(gap.fingerprints()::contains).flatMap(broken -> session.close(CloseReason.BROKEN)));
		}

		/* The session is asked under the lock of the handle, and what it produced is told with no lock held, as the
		   listener may call back into the handle, one thread at a time and in the order the session produced it.
		   Nothing is told after a Close or once the opener closed. */
		private void ask(final Supplier<Optional<? extends SealedMessage>> call) {
			synchronized (this) {
				call.get().filter(message -> !ended).ifPresent(message -> {
					ended = message instanceof Close;
					untold.add(message);
				});
			}

			untold.drain(message -> guarded(() -> listener.received(message)));
		}

		private void writabilityChanged(final boolean writable) {
			if (!ended) {
				guarded(() -> listener.writabilityChanged(writable));
			}
		}

		/* A Close the opener sends ends the session like close does */
		@Override
		public synchronized void send(final SealedMessage message) {
			if (session == null) {
				throw new IllegalStateException("The session is not open yet");
			}

			session.send(message);

			if (message instanceof Close) {
				stop();
			}
		}

		@Override
		public boolean isWritable() {
			return Optional.ofNullable(chain).map(joined -> joined.outbox().isWritable()).orElse(false);
		}

		@Override
		public int maxDataBytes() {
			return Optional.ofNullable(chain).map(joined -> joined.context().maxPayloadBytes()
				- SessionCipher.OVERHEAD - SealedCodec.DATA_OVERHEAD).orElse(0);
		}

		@Override
		public synchronized void close(final CloseReason reason) {
			stop();

			if (session != null) {
				session.close(reason);
			}
		}

		/* What the session produced and the listener was not told yet is dropped with it */
		private void stop() {
			ended = true;
			untold.clear();
		}
	}
}
