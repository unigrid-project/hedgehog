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

import io.netty.channel.Channel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.model.geo.HostLiteral;
import org.unigrid.hedgehog.model.gridnode.GridnodeSetup;
import org.unigrid.hedgehog.model.gridnode.SealingKey;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Gap;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.chain.WriterKey;
import org.unigrid.hedgehog.proxy.exit.ExitRelay;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.ClientSession;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.NoiseNk;
import org.unigrid.hedgehog.proxy.session.Session;
import org.unigrid.hedgehog.proxy.session.SessionContext;

/* The exit of this node: an entry of a chain it hosts that opens under this gridnode's sealing key is an opening for
   it and is answered, and its session is relayed to the destination by an ExitRelay, which hears every message the
   session opens, is ticked once a second, closed for a gap in its client's feed that names it and forgotten once it
   ended. Nothing on the chain names the exit, so every entry that no relay recognises is tried as an opening: one key
   agreement and one tag check for each entry of the smallest class, which is what an opening fills, and the host
   already caps how many entries a stream brings in each second. Each session writes as a writer of its own, so the
   feeds on a chain do not tell which sessions one exit relays. What a session writes is stemmed and never flooded by
   this node first, so that the first member to flood it is not its exit, and it never goes to the members the client
   attached through, which know the client: the members that first flooded the client's entries here.

   The outbox of a session does not bound what waits in it, and the relays hear of a pause at the next tick, so in the
   worst case the exit holds one second of what all its destinations send. */
@Slf4j @Eager @ApplicationScoped
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true) /* The proxy of a normal scoped bean needs it */
public class ChainExit implements ChainDrain {
	/* A first estimate of what one gridnode relays at once: each relay holds a connection to a destination and up
	   to ExitRelay.BACKLOG_ENTRIES entries of what the client sent it. An opening beyond it is left unanswered, so
	   the client tries another exit after its timeout. */
	static final int MAX_RELAYS = 256;

	/* The node hosts several chains, those of its group and the retired ones that drain, so the writers of one chain
	   that flood it with openings leave at least half of it to the others */
	static final int RELAYS_PER_CHAIN = MAX_RELAYS / 2;

	/* A writer is one client on one chain, whose sessions it spreads over the members of its group at random: the
	   most streams its SOCKS listener takes (512) bring a quarter of the node to each member of a group of eight */
	static final int RELAYS_PER_WRITER = MAX_RELAYS / 4;

	/* A client posts its Connect as soon as the answer reaches it, so a relay that heard nothing from it this long
	   after its answer has nobody behind it, and gives its place back long before the idle limit of the spork */
	static final long CONNECT_ARRIVAL_MILLIS = 30_000;

	/* How far the clocks of a client and its exit may differ */
	static final long CLOCK_SKEW_MILLIS = 10_000;

	/* A client gives up on an opening after ClientSession.ACCEPT_TIMEOUT_MILLIS, so an opening stamped longer ago
	   than that, allowing for the skew between the clocks of client and exit, is answered for nobody */
	static final long OPENING_MAX_AGE_MILLIS = ClientSession.ACCEPT_TIMEOUT_MILLIS + CLOCK_SKEW_MILLIS;

	/* The most openings the record of answered ones holds. Past it the eldest is let go, and only its stamp then keeps
	   it from a second answer, which holds once it is OPENING_MAX_AGE_MILLIS old. */
	static final int MAX_ANSWERED = 4096;

	/* The relays that ended while their outbox still held what the chain had no room for. They hold no place among the
	   relays, so they are bounded on their own, by as many as there are relays; past it a relay that ends goes at once,
	   and what waited in its outbox goes with it. */
	static final int MAX_DRAINING = MAX_RELAYS;

	/* The writers of this exit's sessions that hold a place on one chain: those of its relays, of the relays that drain
	   and of the sessions that ended, until their Close aged out of the chain, or for as long as the chain lasts when
	   it never got there. An opening beyond it is left unanswered, so the client tries another exit after its timeout.
	   Together the exits of a group of eight take at most half of the writers a chain takes, which leaves the other
	   half to its clients. */
	static final int MAX_WRITERS_PER_CHAIN = HostedChain.MAX_WRITERS / 16;

	private static final int TICK_SECONDS = 1;

	private record RelayKey(ChainId chainId, WriterId clientWriter, long session) {
		/* One session of a client's writer on a chain, numbered in the order this node answered them */
	}

	private record Relayed(Chain chain, FeedWriter writer, ChainOutbox outbox, Session session, ExitRelay relay,
		long acceptedAt, AtomicBoolean heard) {
		/* An accepted session on the chain it was answered on, the writer it writes as and the outbox of that
		   writer, when its answer went out and whether its client was heard from since */
	}

	private record Draining(Relayed relayed, long until) {
		/* A relay that ended, whose outbox places what waits in it until the chain took it all or the time is up */
	}

	private record Ended(ChainId chainId, long until) {
		/* The chain of the writer of a session that ended, and until when it holds a place there and its entries may
		   still come back */
	}

	private record Opening(ChainId chainId, ByteBuffer message) {
		/* The handshake message of an opening this node answered, on the chain it answered it on */
	}

	private final ChainHost host;
	private final ProxySettingsSource settings;
	private final SealingKey sealingKey;
	private final LongSupplier clock;
	private final SecureRandom random;
	private final ExitContexts contexts;
	private final ScheduledExecutorService scheduler;
	private final Map<RelayKey, Relayed> relays = new ConcurrentHashMap<>();
	private final Map<RelayKey, Draining> draining = new ConcurrentHashMap<>();
	private final AtomicLong sessions = new AtomicLong();

	/* The writers of the sessions that ended, as a chain the host rebuilt takes their entries in again from the
	   members until they age out. The share of each chain bounds them, as no session starts on a chain where the
	   writers of this exit hold it. */
	private final Map<WriterId, Ended> ended = new ConcurrentHashMap<>();

	/* The key of the writer that the next session takes. An entry is tried as an opening under it, as the answer names
	   the writer, and another is drawn only once a session took it, so an entry that opens nothing draws no key. Only
	   accept reads and writes it, under the monitor of this exit. */
	private WriterKey spare;

	/* The openings this node answered and when, oldest first, kept for the retention of their chain, and at least as
	   long as an opening stays young, whatever became of their relays and their chain: a chain the host rebuilt
	   fetches the feeds of its writers anew. Only accept reads and writes it, under the monitor of this exit. */
	private final Map<Opening, Long> answered = new LinkedHashMap<>();

	/* The host keeps its listeners for good, so a stopped exit must ignore what it is still told */
	private volatile boolean stopped;

	@Inject
	public ChainExit(final ChainHost host, final ProxySettingsSource settings, final SealingKey sealingKey) {
		this(host, settings, sealingKey, GridnodeSetup::announcedAddress);
	}

	ChainExit(final ChainHost host, final ProxySettingsSource settings, final SealingKey sealingKey,
		final Supplier<String> announced) {

		this(host, settings, sealingKey, System::currentTimeMillis, new SecureRandom(),
			ExitContexts.production(announcedLocal(sealingKey, announced)),
			Executors.newSingleThreadScheduledExecutor(ChainExit::daemonThread));
	}

	ChainExit(final ChainHost host, final ProxySettingsSource settings, final SealingKey sealingKey,
		final LongSupplier clock, final SecureRandom random, final ExitContexts contexts,
		final ScheduledExecutorService scheduler) {

		this.host = host;
		this.settings = settings;
		this.sealingKey = sealingKey;
		this.clock = clock;
		this.random = random;
		this.contexts = contexts;
		this.scheduler = scheduler;
	}

	/* The address the exit connects from: the announced one, where this machine has it. Only a gridnode announces
	   an address, and the daemon has checked that one before the container starts. The operator is told once, as
	   the exit starts, when the exit cannot leave from the address clients choose it by, without naming it. */
	static Optional<InetAddress> announcedLocal(final SealingKey sealingKey, final Supplier<String> announced) {
		if (sealingKey.publicKey().isEmpty()) {
			return Optional.empty();
		}

		final String address = announced.get();
		final Optional<InetAddress> local = ExitContexts.localAddressOf(address);

		if (HostLiteral.of(address).filter(InetAddress::isLoopbackAddress).isPresent()) {
			log.info("The gridnode announces a loopback address, so its exit reaches no public destination");
		} else if (local.isEmpty()) {
			log.info("The gridnode announces no address of this host, as behind 1:1 NAT, so its exit connects "
				+ "from the address the host chooses");
		}

		return local;
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

	int drainingRelays() {
		return draining.size();
	}

	/* The host tells the entries of a chain one at a time and in the order the chain appended them, so the session an
	   opening starts is registered before the next entry of the chain, its client's Connect among them, is told. An
	   entry of a writer of this node's sessions is no opening, and one of a client that a relay of that client
	   recognises goes to that relay, which also breaks on an entry past the next one. */
	void appended(final ChainId chainId, final Entry entry) {
		if (stopped || isOwn(entry.header().writer())) {
			return;
		}

		final Optional<Relayed> known = relays.entrySet().stream().filter(held -> isOf(held.getKey(), chainId,
			entry.header().writer()) && held.getValue().session().recognises(entry)).map(Map.Entry::getValue)
			.findFirst();

		if (known.isPresent()) {
			relay(known.get(), entry);
		} else if (isSmallest(chainId, entry)) {
			accept(chainId, entry);
		}
	}

	private static boolean isOf(final RelayKey key, final ChainId chainId, final WriterId clientWriter) {
		return key.chainId().equals(chainId) && key.clientWriter().equals(clientWriter);
	}

	/* The writers of the sessions that ended count as well, as a chain the host rebuilt takes their entries in again.
	   The tick moves a writer from the relays to those that drain and on to the record of ended ones, each time
	   putting it in the next place before taking it out of the last, so looking in that same order never misses it. */
	private boolean isOwn(final WriterId writer) {
		return relays.values().stream().anyMatch(relayed -> writesAs(relayed, writer))
			|| draining.values().stream().anyMatch(held -> writesAs(held.relayed(), writer))
			|| ended.containsKey(writer);
	}

	private static boolean writesAs(final Relayed relayed, final WriterId writer) {
		return relayed.writer().writer().equals(writer);
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

	/* A gap in the feed of a session's client that skipped an entry under a tag the session looks out for lost
	   entries of that session, which cannot go on without them. The gap of another writer says nothing about it. */
	void gapped(final ChainId chainId, final Gap gap) {
		if (!stopped) {
			relays.entrySet().stream().filter(held -> isOf(held.getKey(), chainId, gap.writer())
				&& held.getValue().session().isNamedIn(gap))
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

	/* Run once a second: ends the sessions on the chains the host dropped or rebuilt and those whose feed ended, places
	   what the outboxes hold and ticks every relay. A relay that ended gives its place up, and its writer and outbox
	   go once the outbox has nothing left to place: the Close of a session whose chain had no room waits there. */
	void tick() {
		final long now = clock.getAsLong();

		relays.values().stream().filter(relayed -> relayed.relay().isOpen() && isStale(relayed))
			.forEach(relayed -> guarded(() -> relayed.relay().close(CloseReason.BROKEN)));
		Stream.concat(relays.values().stream(), draining.values().stream().map(Draining::relayed))
			.forEach(relayed -> guarded(relayed.outbox()::tick));
		relays.values().forEach(relayed -> guarded(() -> tick(relayed, now)));
		relays.entrySet().stream().filter(held -> !held.getValue().relay().isOpen()).toList()
			.forEach(held -> drain(held.getKey(), held.getValue(), now));
		draining.entrySet().stream().filter(held -> isDrained(held.getValue(), now)).toList()
			.forEach(held -> {
				letGo(held.getValue().relayed(), now);
				draining.remove(held.getKey(), held.getValue());
			});
		forgetEnded(now);
	}

	/* An outbox tells a change of its writability once, so its level is handed to its relay every second */
	private static void tick(final Relayed relayed, final long now) {
		relayed.relay().setChainWritable(relayed.outbox().isWritable());
		relayed.relay().tick();

		if (!relayed.heard().get() && now - relayed.acceptedAt() >= CONNECT_ARRIVAL_MILLIS) {
			relayed.relay().close(CloseReason.IDLE);
		}
	}

	/* One opening at a time, so the shares hold however many chains offer openings at once. What is cheap to check
	   comes before the key agreement: the stamp of the entry, the role of this node, the room for one more relay and
	   whether the opening was answered already. A node without a sealing key is no gridnode and opens nothing. */
	private synchronized void accept(final ChainId chainId, final Entry entry) {
		final Optional<ProxySettings> proxy = settings.current();
		final long now = clock.getAsLong();

		if (proxy.isPresent() && isCandidate(chainId, entry, now)) {

			final Opening opening = openingOf(chainId, entry);

			forgetAnswered(now - Math.max(proxy.get().chainLimits().retentionMillis(),
				OPENING_MAX_AGE_MILLIS + CLOCK_SKEW_MILLIS));

			if (!answered.containsKey(opening)) {
				sealingKey.staticKey().ifPresent(key -> host.chain(chainId)
					.ifPresent(chain -> answer(chain, entry, opening, key, proxy.get())));
			}
		}
	}

	/* An answer of this node that came back while the session it opened was still being registered waited for the
	   monitor, and finds its writer among the relays now */
	private boolean isCandidate(final ChainId chainId, final Entry entry, final long now) {
		return !isOwn(entry.header().writer()) && isYoung(entry, now) && isExitOn(chainId)
			&& hasRoom(chainId, entry.header().writer(), now);
	}

	/* An opening is young from as far ahead of this clock as the skew allows to OPENING_MAX_AGE_MILLIS behind it, so
	   one answered at the start of that span is kept in the record for all of it */
	private static boolean isYoung(final Entry entry, final long now) {
		final long stamp = entry.header().timestamp();

		return now - stamp <= OPENING_MAX_AGE_MILLIS && stamp - now <= CLOCK_SKEW_MILLIS;
	}

	private static Opening openingOf(final ChainId chainId, final Entry entry) {
		return new Opening(chainId, ByteBuffer.wrap(Arrays.copyOf(entry.payload(), NoiseNk.MESSAGE_SIZE)));
	}

	/* Lets go of the openings answered before the cutoff, and of the eldest beyond the bound */
	private void forgetAnswered(final long cutoff) {
		final Iterator<Long> times = answered.values().iterator();

		while (times.hasNext()) {
			final long answeredAt = times.next();

			if (answeredAt >= cutoff && answered.size() < MAX_ANSWERED) {
				return;
			}

			times.remove();
		}
	}

	/* An opening fills the smallest class of its chain, so no other entry is worth a key agreement */
	private boolean isSmallest(final ChainId chainId, final Entry entry) {
		return host.chain(chainId).filter(chain -> chain.limits().smallestClassBytes() == entry.size()).isPresent();
	}

	/* A node that is no active gridnode of a group is no exit, and a chain it only follows as a client is none of its
	   own: the clients of that chain do not choose it */
	private boolean isExitOn(final ChainId chainId) {
		return host.group().isPresent()
			&& (host.hostedChains().contains(chainId) || host.retiredChains().contains(chainId));
	}

	/* The node, the chain and the writer of the opening each have a share of the relays, and the writers of the
	   sessions a share of the places on the chain, of which the chain must have one left for the new writer of the
	   session. An exit at a limit stays silent, as it would for an opening that is not for it, and the client moves on
	   after its timeout. */
	private boolean hasRoom(final ChainId chainId, final WriterId writer, final long now) {
		return relays.size() < MAX_RELAYS
			&& held(relayed -> relayed.chain().id().equals(chainId)) < RELAYS_PER_CHAIN
			&& held(relayed -> relayed.session().peer().equals(writer)) < RELAYS_PER_WRITER
			&& chainHasRoom(chainId, writer, now);
	}

	/* The session also needs a successor to stem what it writes to, other than the members its client attached
	   through, and an exit without one stays silent as well */
	private boolean chainHasRoom(final ChainId chainId, final WriterId writer, final long now) {
		return writersOn(chainId, now) < MAX_WRITERS_PER_CHAIN
			&& host.hosted(chainId).filter(HostedChain::takesNewWriters).isPresent()
			&& host.canStem(chainId, avoidFor(chainId, writer));
	}

	/* The member streams the entries of a client's writer first came in on, those the client attached through */
	private Set<Channel> avoidFor(final ChainId chainId, final WriterId clientWriter) {
		return host.hosted(chainId).map(hosted -> hosted.firstFlooders(clientWriter)).orElse(Set.of());
	}

	/* The writers of this exit that hold a place on the chain */
	long writersOn(final ChainId chainId, final long now) {
		final long drains = draining.values().stream().filter(held -> held.relayed().chain().id().equals(chainId))
			.count();
		final long gone = ended.values().stream().filter(writer -> writer.chainId().equals(chainId)
			&& writer.until() >= now).count();

		return held(relayed -> relayed.chain().id().equals(chainId)) + drains + gone;
	}

	private long held(final Predicate<Relayed> which) {
		return relays.values().stream().filter(which).count();
	}

	/* The relays this node holds on one chain */
	private Stream<Relayed> relaysOn(final ChainId chainId) {
		return relays.values().stream().filter(relayed -> relayed.chain().id().equals(chainId));
	}

	/* The session writes as the spare writer, which goes back for the next trial when the entry opens nothing. The
	   relay hears whether the chain takes what it sends before it hears the client. The tick hands the outbox's level
	   to the relay right after the outbox ticked, which is where it would tell a change, so it tells nobody. */
	private void answer(final Chain chain, final Entry entry, final Opening opening, final NoiseNk.StaticKey key,
		final ProxySettings proxy) {

		final WriterKey taken = takeSpare();
		final FeedWriter writer = new FeedWriter(taken, chain.id());
		final ChainOutbox outbox = new ChainOutbox(host, chain.id(), writer.writer(), writable -> { },
			ChainOutbox.Mode.STEM, () -> avoidFor(chain.id(), entry.header().writer()));
		final SessionContext context = new SessionContext(writer, outbox, clock, random, proxy);
		final Optional<Session> accepted = Session.tryAccept(context, entry, key);

		if (accepted.isEmpty()) {
			spare = taken;
			return;
		}

		final Session session = accepted.get();
		final ExitRelay relay = new ExitRelay(session, contexts.of(proxy));

		answered.put(opening, clock.getAsLong());
		relay.setChainWritable(outbox.isWritable());
		relays.put(new RelayKey(chain.id(), session.peer(), sessions.incrementAndGet()),
			new Relayed(chain, writer, outbox, session, relay, clock.getAsLong(), new AtomicBoolean()));
	}

	/* A spare that a failure took along is never handed out again, as its writer may have posted */
	private WriterKey takeSpare() {
		final WriterKey taken = spare == null ? WriterKey.generate(random) : spare;

		spare = null;
		return taken;
	}

	/* A chain the host rebuilt, because the spork changed its limits or the proxy was off for a while, is not the one
	   the session went on, and a feed whose outbox ended takes nothing more */
	private boolean isStale(final Relayed relayed) {
		return relayed.outbox().hasEnded() || host.chain(relayed.chain().id())
			.filter(chain -> chain == relayed.chain()).isEmpty();
	}

	/* A relay that ended has posted all it will. Whether its outbox still holds any of it is what counts, and not
	   whether the writer is paused, as a member may keep any writer paused for good. */
	private void drain(final RelayKey key, final Relayed relayed, final long now) {
		if (relayed.outbox().isDrained() || draining.size() >= MAX_DRAINING) {
			letGo(relayed, now);
		} else {
			draining.put(key, new Draining(relayed, now + relayed.chain().limits().retentionMillis()));
		}

		relays.remove(key, relayed);
	}

	/* A relay drains for the retention of its chain at most: what waited longer would be refused as too old for the
	   chain, so nothing that could still be placed is given up on */
	private static boolean isDrained(final Draining held, final long now) {
		return held.relayed().outbox().isDrained() || now >= held.until();
	}

	/* What the writer of the session posted may come back for as long as its chain keeps entries, and a chain forgets
	   the feed once its last entry is that old. The writer may have stamped ahead of the clock, which then stepped
	   back, so that time runs from its latest stamp. A Close that never reached the chain, as the outbox ended or the
	   relay went with it still waiting, leaves a feed that never ends where the writer has one, which holds its place
	   for as long as the host holds the chain, and so does a writer whose entries are still in the stem, as they come
	   in later; a writer whose answer never got anywhere has none. The writer is in the record before its relay leaves
	   the others. It posts nothing more, so a pause a member put on it is lifted, as nothing would ever lift it. */
	private void letGo(final Relayed relayed, final long now) {
		final WriterId writer = relayed.writer().writer();
		final boolean placed = relayed.outbox().isDrained() && !relayed.outbox().hasEnded();
		final boolean lasting = !placed && host.hosted(relayed.chain().id()).filter(hosted -> hosted.getChain()
			.heads().containsKey(writer) || hosted.getEmbargo().holdsAnyOf(writer)).isPresent();
		final long latest = Math.max(now, relayed.writer().timestamp());

		ended.put(writer, new Ended(relayed.chain().id(),
			lasting ? Long.MAX_VALUE : latest + relayed.chain().limits().retentionMillis()));
		host.pauses().resume(relayed.chain().id(), writer);
	}

	private void forgetEnded(final long now) {
		ended.values().removeIf(gone -> gone.until() < now || host.chain(gone.chainId()).isEmpty());
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
