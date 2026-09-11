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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryHeader;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.Gap;
import org.unigrid.hedgehog.proxy.chain.WriterKey;
import org.unigrid.hedgehog.proxy.exit.ExitRelay;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Session;
import org.unigrid.hedgehog.proxy.session.SessionContext;

/* The exit of this node: an OPEN that names this gridnode on a chain it hosts is answered, and its session is relayed
   to the destination by an ExitRelay, which hears every message the session opens, is ticked once a second, closed
   for a gap in its client's feed and forgotten once it ended. The sessions of a chain write through one feed of this
   node, so a fingerprint has one session per chain: a second OPEN of it, from whichever writer, is left unanswered,
   as a session of its own would write under the same feed and fingerprint and break the first.

   The outbox of a feed does not bound what waits in it, and the relays hear of a pause at the next tick, so in the
   worst case the exit holds one second of what all its destinations send. */
@Slf4j @Eager @ApplicationScoped
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true) /* The proxy of a normal scoped bean needs it */
public class ChainExit implements ChainDrain {
	/* A first estimate of what one gridnode relays at once: each relay holds a connection to a destination and up
	   to ExitRelay.BACKLOG_ENTRIES entries of what the client sent it. An OPEN beyond it is left unanswered, so
	   the client tries another exit. */
	static final int MAX_RELAYS = 256;

	/* The node hosts several chains, those of its group and the retired ones that drain, so the writers of one chain
	   that flood it with OPENs leave at least half of it to the others */
	static final int RELAYS_PER_CHAIN = MAX_RELAYS / 2;

	/* A writer is one client on one chain, whose sessions the fingerprints spread over the members of its group: the
	   most streams its SOCKS listener takes (512) bring a quarter of the node to each member of a group of eight */
	static final int RELAYS_PER_WRITER = MAX_RELAYS / 4;

	/* A client posts its Connect as soon as the ACCEPT reaches it, so a relay that heard nothing from it this long
	   after its ACCEPT has nobody behind it, and gives its place back long before the idle limit of the spork */
	static final long CONNECT_ARRIVAL_MILLIS = 30_000;

	private static final int TICK_SECONDS = 1;

	private record RelayKey(ChainId chainId, Fingerprint fingerprint) {
		/* One session of a fingerprint on a chain */
	}

	private record ExitChain(Chain chain, FeedWriter writer, ChainOutbox outbox) {
		/* This node's feed on one chain, where every session it accepts there writes */
	}

	private record Relayed(ExitChain feed, Session session, ExitRelay relay, long acceptedAt, AtomicBoolean heard) {
		/* An accepted session, when its ACCEPT went out and whether its client was heard from since */
	}

	private final ChainHost host;
	private final ProxySettingsSource settings;
	private final GridnodeDirectory directory;
	private final LongSupplier clock;
	private final SecureRandom random;
	private final ExitContexts contexts;
	private final ScheduledExecutorService scheduler;
	private final Map<ChainId, ExitChain> chains = new ConcurrentHashMap<>();
	private final Map<RelayKey, Relayed> relays = new ConcurrentHashMap<>();

	/* The host keeps its listeners for good, so a stopped exit must ignore what it is still told */
	private volatile boolean stopped;

	@Inject
	public ChainExit(final ChainHost host, final ProxySettingsSource settings, final GridnodeDirectory directory) {
		this(host, settings, directory, System::currentTimeMillis, new SecureRandom(), ExitContexts.production(),
			Executors.newSingleThreadScheduledExecutor(ChainExit::daemonThread));
	}

	ChainExit(final ChainHost host, final ProxySettingsSource settings, final GridnodeDirectory directory,
		final LongSupplier clock, final SecureRandom random, final ExitContexts contexts,
		final ScheduledExecutorService scheduler) {

		this.host = host;
		this.settings = settings;
		this.directory = directory;
		this.clock = clock;
		this.random = random;
		this.contexts = contexts;
		this.scheduler = scheduler;
	}

	private static Thread daemonThread(final Runnable body) {
		final Thread thread = new Thread(body, "chain-exit");

		thread.setDaemon(true);
		return thread;
	}

	@PostConstruct
	void start() {
		host.addEntryListener(this::appended);
		host.addGapListener(this::gapped);
		host.addDrain(this);
		scheduler.scheduleWithFixedDelay(() -> guarded(this::tick), TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
	}

	@PreDestroy
	void stop() {
		stopped = true;
		scheduler.shutdownNow();
		contexts.close();
	}

	int openRelays() {
		return (int) held(relayed -> relayed.relay().isOpen());
	}

	int heldRelays() {
		return relays.size();
	}

	int heldChains() {
		return chains.size();
	}

	/* The host tells the entries of a chain one at a time and in the order the chain appended them, so the session an
	   OPEN starts is registered before the next entry of the chain, its client's Connect among them, is told */
	void appended(final ChainId chainId, final Entry entry) {
		if (stopped) {
			return;
		}

		final Relayed known = relays.get(new RelayKey(chainId, entry.header().fingerprint()));

		if (known != null) {
			relay(known, entry);
		} else if (entry.header().kind() == EntryKind.OPEN) {
			accept(chainId, entry);
		}
	}

	/* The Close of a session that failed to open an entry of its client reaches the relay like any message. A relay
	   that failed in another way is ended, as it would otherwise hold its destination half open. */
	private static void relay(final Relayed relayed, final Entry entry) {
		try {
			relayed.session().receive(entry).ifPresent(message -> {
				relayed.heard().set(true);
				relayed.relay().handle(message);
			});
		} catch (RuntimeException ex) {
			log.warn("Closing an exit relay after {}", ex.getClass().getName());
			relayed.relay().close(CloseReason.BROKEN);
		}
	}

	/* A gap in the feed of a session's client lost entries of that session, which cannot go on without them. The gap
	   of another writer says nothing about it, as any writer can leave one that names any fingerprint. */
	void gapped(final ChainId chainId, final Gap gap) {
		if (!stopped) {
			relays.entrySet().stream().filter(held -> held.getKey().chainId().equals(chainId)
				&& gap.fingerprints().contains(held.getKey().fingerprint())
				&& held.getValue().session().peer().equals(gap.writer()))
				.forEach(held -> guarded(() -> held.getValue().relay().close(CloseReason.BROKEN)));
		}
	}

	/* Ends the sessions this node relays on one chain and tells their clients why, each through its relay's own close,
	   which lets go of the destination whatever becomes of the Close; the tick then forgets the relay and frees its
	   place. A close that fails keeps no other from running, and the first Error is thrown once all of them ran. */
	public void closeChain(final ChainId chainId, final CloseReason reason) {
		if (stopped) {
			return;
		}

		Error first = null;

		for (final Relayed relayed : relaysOn(chainId).toList()) {
			try {
				guarded(() -> relayed.relay().close(reason));
			} catch (Error ex) {
				first = first == null ? ex : first;
			}
		}

		if (first != null) {
			throw first;
		}
	}

	/* A stopped exit runs no session, whatever its relays were left in */
	@Override
	public boolean holds(final ChainId chainId) {
		return !stopped && relaysOn(chainId).anyMatch(relayed -> relayed.relay().isOpen());
	}

	/* The sessions end in order: the destination sees a close, the client's connection closes, and a session still
	   waiting for its reply fails as a general failure */
	@Override
	public void expired(final ChainId chainId) {
		closeChain(chainId, CloseReason.DONE);
	}

	/* Run once a second: ends the sessions of the chains the host dropped or rebuilt, places what the outboxes hold,
	   ticks every relay and forgets the relays that ended */
	void tick() {
		final long now = clock.getAsLong();

		chains.values().stream().filter(this::isStale).toList().forEach(this::retire);
		chains.values().forEach(feed -> guarded(feed.outbox()::tick));
		relays.values().forEach(relayed -> guarded(() -> tick(relayed, now)));
		relays.values().removeIf(relayed -> !relayed.relay().isOpen());
	}

	/* An outbox tells a change of its writability once, so its level is handed to each of its relays every second */
	private static void tick(final Relayed relayed, final long now) {
		relayed.relay().setChainWritable(relayed.feed().outbox().isWritable());
		relayed.relay().tick();

		if (!relayed.heard().get() && now - relayed.acceptedAt() >= CONNECT_ARRIVAL_MILLIS) {
			relayed.relay().close(CloseReason.IDLE);
		}
	}

	/* One OPEN at a time, so the shares hold however many chains offer OPENs at once */
	private synchronized void accept(final ChainId chainId, final Entry open) {
		final Optional<ProxySettings> proxy = settings.current();
		final Optional<String> own = directory.self();

		if (proxy.isPresent() && own.isPresent() && isExitOn(chainId) && hasRoom(chainId, open.header())) {
			host.chain(chainId).map(this::feedOn).ifPresent(feed -> answer(feed, open, own.get(), proxy.get()));
		}
	}

	/* A node that is no active gridnode of a group is no exit, and a chain it only follows as a client is none of its
	   own: the clients of that chain do not choose it */
	private boolean isExitOn(final ChainId chainId) {
		return host.group().isPresent()
			&& (host.hostedChains().contains(chainId) || host.retiredChains().contains(chainId));
	}

	/* A fingerprint that has a session on the chain already has its answer, and the node, the chain and the OPEN's
	   writer each have a share of the relays */
	private boolean hasRoom(final ChainId chainId, final EntryHeader open) {
		return !relays.containsKey(new RelayKey(chainId, open.fingerprint())) && relays.size() < MAX_RELAYS
			&& held(relayed -> relayed.feed().chain().id().equals(chainId)) < RELAYS_PER_CHAIN
			&& held(relayed -> relayed.session().peer().equals(open.writer())) < RELAYS_PER_WRITER;
	}

	private long held(final Predicate<Relayed> which) {
		return relays.values().stream().filter(which).count();
	}

	/* The relays this node holds on one chain */
	private Stream<Relayed> relaysOn(final ChainId chainId) {
		return relays.values().stream().filter(relayed -> relayed.feed().chain().id().equals(chainId));
	}

	/* The relay hears whether the chain takes what it sends before it hears the client */
	private void answer(final ExitChain feed, final Entry open, final String own, final ProxySettings proxy) {
		final SessionContext context = new SessionContext(feed.writer(), feed.outbox(), clock, random, proxy);

		Session.accept(context, open, own).ifPresent(session -> {
			final ExitRelay relay = new ExitRelay(session, contexts.of(proxy));

			relay.setChainWritable(feed.outbox().isWritable());
			relays.put(new RelayKey(feed.chain().id(), session.fingerprint()),
				new Relayed(feed, session, relay, clock.getAsLong(), new AtomicBoolean()));
		});
	}

	/* This node's feed on the chain the host holds now. A chain the host rebuilt, because the spork changed its limits
	   or the proxy was off for a while, is not the one an earlier feed went on: the sessions of that feed end, and a
	   feed of its own starts on the new chain. The tick hands the outbox's level to the relays right after the outbox
	   ticked, which is where it would tell a change, so it tells nobody. */
	private ExitChain feedOn(final Chain chain) {
		final ExitChain known = chains.get(chain.id());

		if (known != null && known.chain() == chain) {
			return known;
		}

		if (known != null) {
			retire(known);
		}

		final FeedWriter writer = new FeedWriter(WriterKey.generate(random), chain.id());
		final ExitChain feed = new ExitChain(chain, writer, new ChainOutbox(host, chain.id(), writer.writer(),
			writable -> { }));

		chains.put(chain.id(), feed);
		return feed;
	}

	private boolean isStale(final ExitChain feed) {
		return host.chain(feed.chain().id()).filter(chain -> chain == feed.chain()).isEmpty();
	}

	/* What the sessions of a feed whose chain is gone write reaches nobody */
	private void retire(final ExitChain feed) {
		chains.remove(feed.chain().id(), feed);
		relays.values().stream().filter(relayed -> relayed.feed() == feed)
			.forEach(relayed -> guarded(() -> relayed.relay().close(CloseReason.BROKEN)));
	}

	/* A relay, a feed or a tick that fails is logged by the class of the failure alone, as a message may name a
	   destination, and the others still get their turn: an exception that left a scheduled tick would end the
	   schedule for good */
	private static void guarded(final Runnable step) {
		try {
			step.run();
		} catch (RuntimeException ex) {
			log.warn("A step of the chain exit failed with {}", ex.getClass().getName());
		}
	}
}
