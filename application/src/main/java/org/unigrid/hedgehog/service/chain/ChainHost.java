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
import io.netty.util.AttributeKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.ChainStem;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.ChainLimits;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.HeadsDiff;
import org.unigrid.hedgehog.proxy.chain.Offer;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.Epoch;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.service.chain.DeliveryQueue.Delivery;

/* Hosts the chains of this node's shard group while a valid communication spork says the proxy is on. Ticks on a
   scheduler of its own, so a spork that changes while nothing else happens still starts and stops the hosting. */
@Slf4j
@Eager @ApplicationScoped
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true) /* The proxy of a normal scoped bean needs it */
public class ChainHost implements ChainService, StemHost {
	/* A client keeps one writer on a chain, and a few chains at most, so more is a stream hoarding places */
	static final int MAX_WRITERS_PER_STREAM = 4;

	/* A chain this node stopped hosting is kept for the sessions that still run on it and the members that lag
	   while the epoch it was created for is at most this many epochs behind the current one, and dropped when
	   it is more behind. It is the epoch of the chain that counts, not how often the topology made the node
	   rebuild its hosting since. */
	static final int RETIRED_EPOCHS = 4;

	/* How long the sessions on a retired chain may run on before they are ended and the chain is dropped. A chain
	   is never kept longer than the epoch it ended, whatever the spork says, so a node holds or follows the current
	   chain and one that drains, and no more. */
	static final long DRAIN_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(10);

	/* A drain that failed to end the sessions of a chain is told again at every tick, until the chain has been
	   retired for this many graces: then it goes all the same, as one broken drain must not keep chains for good */
	static final int GRACES_UNTIL_DROPPED = 2;

	/* The most retired chains held, whatever their age, as a node must not end up holding every chain it ever
	   hosted: the oldest epoch goes first, and within it the chain that retired first. Maintenance trims a retired
	   chain every second, so an idle one holds next to nothing, but the count is bounded all the same. */
	static final int MAX_RETIRED_CHAINS = 1024;

	/* A client follows the chains of the groups it is attached to, and those of the epoch before while their
	   sessions drain: twice as many as it attaches to, so that the turn of an epoch never keeps it from following
	   the chains it joins in the new one */
	static final int MAX_FOLLOWED = 2 * ChainClient.MAX_ATTACHMENTS;

	/* What one fetch can ask for and what one answer can hold: a few packets full, however much a peer asks */
	private static final int RANGE_ENTRIES = ChainCodecs.MAX_ENTRIES_PER_PACKET;
	private static final int MAX_RANGES = 8;

	private static final int TICK_SECONDS = 1;

	/* The order of the chain itself, so that the entries of a writer reach the requester in sequence */
	private static final Comparator<Entry> OLDEST_FIRST = Comparator
		.comparingLong((Entry entry) -> entry.header().timestamp()).thenComparing(entry -> entry.header().writer())
		.thenComparingLong(entry -> entry.header().seq());

	/* Set on a stream once its close is awaited, so that however often it joins and leaves it is awaited once */
	private static final AttributeKey<Boolean> CLOSE_AWAITED = AttributeKey.valueOf("chainHostCloseAwaited");

	private final ProxySettingsSource settings;
	private final GridnodeDirectory directory;
	private final ChainTransport transport;
	private final LongSupplier clock;
	private final RandomGenerator random;
	private final ScheduledExecutorService scheduler;
	private final Executor deliveryExecutor;

	private final RandomGenerator stemRandom;
	private final Stem stems;
	private final Listeners<GapListener> gapListeners = new Listeners<>();
	private final Listeners<EntryListener> entryListeners = new Listeners<>();
	private final Listeners<ChainDrain> drains = new Listeners<>();
	private final WriterPauses pauses = new WriterPauses();
	private final StreamBudgets budgets;
	private final FollowedChains followed = new FollowedChains(MAX_FOLLOWED);
	private final Object joins = new Object();

	/* Volatile so that the write which swaps in a finished hosting is seen whole by the netty threads that read it
	   while the refresh thread works on the next one */
	private volatile Hosting hosting = Hosting.OFF;

	/* What a host draws from: picks for the member a reconcile round tells and the ranges it asks for, and stem for
	   the modes, the successors and the deadlines of the stem, which tell an observer whose entries they are when
	   they can be foreseen, so a host of its own draws them from a strong source */
	record Draws(RandomGenerator picks, RandomGenerator stem) {
		/* Two generators, so that a test which decides the member a host picks does not decide the stem with it */

		/* The stem drawn from a generator seeded by the one for the picks, so that hosts given different ones draw
		   unlike each other */
		static Draws of(final RandomGenerator picks) {
			return new Draws(picks, new Random(picks.nextLong()));
		}
	}

	@Inject
	public ChainHost(final ProxySettingsSource settings, final GridnodeDirectory directory,
		final ChainTransport transport) {

		this(settings, directory, transport, System::currentTimeMillis, new Draws(new Random(), new SecureRandom()),
			Executors.newSingleThreadScheduledExecutor(body -> daemonThread(body, "chain-host")),
			Executors.newSingleThreadExecutor(body -> daemonThread(body, "chain-delivery")));
	}

	/* Runs every delivery on the thread that asks for it, so that a post is flooded when it returns */
	ChainHost(final ProxySettingsSource settings, final GridnodeDirectory directory, final ChainTransport transport,
		final LongSupplier clock, final RandomGenerator random, final ScheduledExecutorService scheduler) {

		this(settings, directory, transport, clock, random, scheduler, Runnable::run);
	}

	/* The delivery executor runs what a post hands over: the draining of its chain, on a thread that holds none of the
	   locks of the caller */
	ChainHost(final ProxySettingsSource settings, final GridnodeDirectory directory, final ChainTransport transport,
		final LongSupplier clock, final RandomGenerator random, final ScheduledExecutorService scheduler,
		final Executor deliveryExecutor) {

		this(settings, directory, transport, clock, Draws.of(random), scheduler, deliveryExecutor);
	}

	ChainHost(final ProxySettingsSource settings, final GridnodeDirectory directory, final ChainTransport transport,
		final LongSupplier clock, final Draws draws, final ScheduledExecutorService scheduler,
		final Executor deliveryExecutor) {

		this.settings = settings;
		this.directory = directory;
		this.transport = transport;
		this.clock = clock;
		this.random = draws.picks();
		this.scheduler = scheduler;
		this.deliveryExecutor = deliveryExecutor;
		this.budgets = new StreamBudgets(clock);
		this.stemRandom = draws.stem();

		/* Last, as the stem calls back into the host */
		this.stems = new Stem(this, () -> hosting, clock);
	}

	/* A daemon, so a host that is never stopped still lets the process end */
	private static Thread daemonThread(final Runnable body, final String name) {
		final Thread thread = new Thread(body, name);
		thread.setDaemon(true);
		return thread;
	}

	@PostConstruct
	void start() {
		scheduler.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
	}

	/* An executor that runs on the caller has nothing to shut down */
	@PreDestroy
	void stop() {
		scheduler.shutdownNow();

		if (deliveryExecutor instanceof ExecutorService service) {
			service.shutdownNow();
		}
	}

	/* One tick follows the spork, the topology and the clock, drops the retired chains that are done, then keeps the
	   chains in shape and only then talks to the members, so that a fetch it sets going is never served from entries
	   past the retention window. */
	public void tick() {
		guarded("refresh", this::refresh);
		guarded("drain", this::drain);
		guarded("maintain", this::maintain);
		guarded("reconcile", this::reconcileRound);
	}

	/* A step that fails is logged by its name and the class of the failure, as a message may name an address, and
	   the steps after it and the next tick go on: an exception that escaped would cancel the schedule for good.
	   An Error is no failure to carry on from, so it passes, but not before a line in the log, as the schedule
	   would end with it unseen. */
	private void guarded(final String name, final Runnable step) {
		try {
			step.run();
		} catch (RuntimeException ex) {
			log.warn("The {} step of the chain host tick failed with {}", name, ex.getClass().getName());
		} catch (Error ex) {
			log.error("The {} step of the chain host tick ended with {}", name, ex.getClass().getName());
			throw ex;
		}
	}

	/* The chains of this node's group in the current epoch, in index order. Each call reads the hosting once, so
	   this and group() may answer from two different hostings; a caller that needs both takes one read of it. */
	public List<ChainId> hostedChains() {
		return List.copyOf(hosting.current().keySet());
	}

	/* The chains of earlier hostings that are still kept, in the order they retired */
	public List<ChainId> retiredChains() {
		return List.copyOf(hosting.retired().keySet());
	}

	public void addGapListener(final GapListener listener) {
		gapListeners.add(listener);
	}

	public void addEntryListener(final EntryListener listener) {
		entryListeners.add(listener);
	}

	public void addDrain(final ChainDrain drain) {
		drains.add(drain);
	}

	/* The client sessions end theirs after the same grace, as they drain on a timer of their own */
	static long graceMillis(final ProxySettings proxy) {
		return Math.min(DRAIN_GRACE_MILLIS, TimeUnit.SECONDS.toMillis(proxy.reorganizePeriodSeconds()));
	}

	/* A packet may name no chain, which neither the map of a hosting that is off nor the followed chains take */
	Optional<HostedChain> hosted(final ChainId id) {
		return Optional.ofNullable(id).flatMap(named -> hosting.find(named).or(() -> followed.find(named)));
	}

	/* A node outside the group of a chain, such as a client, follows it through the members it joined at: it keeps a
	   replica of the chain, anchored at the heads of their join replies, takes entries in from their streams like a
	   member's and believes their refusals, and posts its own entries through all of them. It passes nothing on, as
	   the members flood to each other. A node follows a few chains at most, none that it hosts itself, and none while
	   the proxy is off or the clock reads before the origin, where there is no epoch to stamp a replica with. The
	   anchors are as trustworthy as the member that sent them: they pin a feed to a head and use up writer slots, and
	   a second member does not undo that, so pass only the join reply of a member the node chose. */
	public boolean follow(final ChainId chainId, final Channel member, final Map<WriterId, Head> anchors) {
		final Optional<ProxySettings> current = settings.current();
		final long now = clock.getAsLong();

		if (current.isEmpty() || now < 0 || chainId == null || hosting.find(chainId).isPresent()) {
			return false;
		}

		return followed.follow(chainId, member, Objects.requireNonNullElse(anchors, Map.of()),
			id -> replicaOf(id, current.get(), now));
	}

	public void unfollow(final ChainId chainId) {
		followed.unfollow(chainId);
	}

	/* Stamped with the epoch it is made in like a hosted chain, though only a hosting retires a chain */
	private HostedChain replicaOf(final ChainId id, final ProxySettings current, final long now) {
		final HostedChain replica = hostedChain(id, current.chainLimits(), current.epochAt(now));

		replica.setRelaying(false);
		return replica;
	}

	public Optional<Chain> chain(final ChainId id) {
		return hosted(id).map(HostedChain::getChain);
	}

	public OptionalInt group() {
		return hosting.group();
	}

	/* Follows the spork, the topology and the clock. Refreshes run one at a time and swap in a hosting whose
	   chains and map are never changed afterwards, so the other calls read it without a lock. The streams to the
	   other members are the exception: they are looked up again on every refresh, also when the hosting is
	   reused, and handed to the chains before a rebuilt hosting is swapped in so that no reader sees a chain
	   without them. */
	public synchronized void refresh() {
		final Hosting previous = hosting;
		final Hosting next = settings.current().map(current -> hostingFor(current, previous)).orElse(Hosting.OFF);

		holdStreams(next);
		hosting = next;
	}

	/* Not private so that a test can tell how often the groups are ranked */
	GroupView viewOf(final ProxySettings current, final long now, final Collection<String> ids) {
		return new GroupView(current, now, ids);
	}

	/* Not private so that a test can hold the hosting itself; nothing in the host calls it */
	Hosting snapshot() {
		return hosting;
	}

	private Hosting hostingFor(final ProxySettings current, final Hosting previous) {
		final long now = clock.getAsLong();

		/* A clock that reads before the origin has no epoch, so it hosts nothing instead of failing the refresh */
		if (now < 0) {
			return Hosting.OFF;
		}

		final Hosting.Basis basis = new Hosting.Basis(current, current.epochAt(now), activeIds(), directory.self());

		/* Ranking every gridnode and hashing every chain id is the dear part of a refresh, which runs about
		   once a second, so a hosting stays while the settings, the epoch and the gridnodes stay what they were */
		return previous.isBuiltFrom(basis) ? previous : rebuilt(basis, now, previous);
	}

	private HostedChain hostedChain(final ChainId id, final ChainLimits limits, final Epoch epoch) {
		return new HostedChain(new Chain(id, limits, clock), epoch, HostedChain.MAX_WRITERS, stemRandom);
	}

	private Hosting rebuilt(final Hosting.Basis basis, final long now, final Hosting previous) {
		final GroupView view = viewOf(basis.settings(), now, basis.activeIds());
		final ChainLimits limits = basis.settings().chainLimits();
		final OptionalInt group = basis.own().map(id -> view.groups().groupOf(id)).orElse(OptionalInt.empty());
		final Map<ChainId, HostedChain> chains = new LinkedHashMap<>();

		for (final ChainId id : basis.own().map(view::chainsOf).orElse(List.of())) {
			chains.put(id, previous.reusable(id, limits)
				.orElseGet(() -> hostedChain(id, limits, basis.epoch())));
		}

		/* Only the chains that retire now: one that stays retired over later rebuilds keeps the time it retired */
		previous.current().entrySet().stream().filter(chain -> !chains.containsKey(chain.getKey()))
			.forEach(chain -> chain.getValue().setRetiredAt(now));

		return new Hosting(Optional.of(basis), group, peersOf(view, group, basis.own()),
			Collections.unmodifiableMap(chains), retiredBy(previous, chains.keySet(), basis.epoch()));
	}

	/* The chains held and not wanted now are retired, in the order they retired. A retired chain that is wanted
	   again leaves them to be a current one, and one whose epoch is more than RETIRED_EPOCHS behind the current
	   epoch is dropped. An epoch ahead of the current one, as after a clock that stepped back, is not behind. This
	   runs when a hosting is rebuilt and only then, since the wanted chains and the epoch change only then: a
	   reused hosting has the retired chains it had, less those the drain dropped. */
	private static Map<ChainId, HostedChain> retiredBy(final Hosting previous, final Set<ChainId> wanted,
		final Epoch now) {

		final Map<ChainId, HostedChain> retired = new LinkedHashMap<>(previous.retired());

		retired.putAll(previous.current());
		retired.keySet().removeAll(wanted);
		retired.values().removeIf(hosted -> now.number() - hosted.getEpoch().number() > RETIRED_EPOCHS);
		dropBeyondTheCap(retired);
		return Collections.unmodifiableMap(retired);
	}

	/* The sort is stable, so within an epoch the chain that retired first goes first */
	private static void dropBeyondTheCap(final Map<ChainId, HostedChain> retired) {
		final List<ChainId> oldest = retired.values().stream()
			.sorted(Comparator.comparingLong((HostedChain hosted) -> hosted.getEpoch().number()))
			.limit(Math.max(0, retired.size() - MAX_RETIRED_CHAINS))
			.map(hosted -> hosted.getChain().id()).toList();

		retired.keySet().removeAll(oldest);
	}

	private static List<String> peersOf(final GroupView view, final OptionalInt group, final Optional<String> own) {
		return group.stream().boxed().flatMap(index -> view.groups().members(index).stream())
			.filter(member -> !own.equals(Optional.of(member))).toList();
	}

	/* Looked up once per refresh and not per packet, because finding a connection scans the topology and resolves
	   the address of the gridnode: the cost is bounded by the size of the group. The chains of one group share one
	   immutable list, which the netty threads read through a volatile field while this thread writes the next one. A
	   stream that is not open is no stream of a member; the list is as old as the refresh, so a stream that closes
	   later is dropped by the next one. A retired chain keeps the members it had when it was current and has their
	   streams looked up again too, so that a member that reconnected is a member of it as well, as an exit may still
	   stem the entries of a session that drains on it. */
	private void holdStreams(final Hosting next) {
		final Map<List<String>, Map<Channel, String>> streams = new HashMap<>();

		next.current().values().forEach(hosted -> hosted.setMembers(next.peers()));
		next.all().filter(hosted -> !hosted.getMembers().isEmpty()).forEach(hosted -> hosted.holdMembers(
			streams.computeIfAbsent(hosted.getMembers(), this::streamsTo)));
	}

	/* The open streams to the members, in their order, each with the id of its gridnode */
	private Map<Channel, String> streamsTo(final List<String> members) {
		final Map<Channel, String> streams = new LinkedHashMap<>();

		members.forEach(member -> lookUp(member).filter(Channel::isActive)
			.ifPresent(stream -> streams.putIfAbsent(stream, member)));
		return streams;
	}

	/* One broken lookup leaves that member without a stream, not every member, and the refresh which runs about
	   once a second must not fail for it. Only the class of the failure is logged, as its message may name an
	   address. */
	private Optional<Channel> lookUp(final String member) {
		try {
			return Objects.requireNonNullElse(transport.streamTo(member), Optional.empty());
		} catch (RuntimeException ex) {
			log.warn("The lookup of a member stream failed with {}", ex.getClass().getName());
			return Optional.empty();
		}
	}

	private Set<String> activeIds() {
		/* The ranking throws for a gridnode without an id, which would fail every refresh */
		return directory.active().stream().map(Gridnode::getId).filter(Objects::nonNull)
			.collect(Collectors.toUnmodifiableSet());
	}

	/* Client feeds are only ever added in here, one join at a time, so no chain gets past its limit. They are
	   removed by leaves and closed streams without the lock, which can only make room. The lock is not the one
	   of refresh(), which waits for stream lookups that a join must not wait for. A stream that takes no writes
	   and one over the joins of its second are refused before the lock. */
	@Override
	public JoinReply join(final Channel peer, final JoinChain request) {
		final JoinReply.JoinReplyBuilder reply = JoinReply.builder().requestId(request.getRequestId());

		if (!peer.isWritable() || !budgets.take(peer, StreamBudgets.Kind.JOINS, 1)) {
			return reply.status(JoinReply.Status.FULL).build();
		}

		final Optional<HostedChain> placed;

		synchronized (joins) {
			placed = placed(peer, request, reply);
		}

		return placed.map(hosted -> admitted(hosted, peer, request.getWriter(), reply)).orElseGet(reply::build);
	}

	/* Attaches the writer to a chain, or writes on the reply the refusal the request earns */
	private Optional<HostedChain> placed(final Channel peer, final JoinChain request,
		final JoinReply.JoinReplyBuilder reply) {

		final Hosting current = hosting;
		final WriterId writer = request.getWriter();

		if (current.settings().isEmpty()) {
			reply.status(JoinReply.Status.DISABLED);
			return Optional.empty();
		}

		if (writer == null) {
			reply.status(JoinReply.Status.FULL);
			return Optional.empty();
		}

		final Optional<HostedChain> placement = current.placement(writer, request.getChainId());

		if (placement.isEmpty()) {
			reply.status(JoinReply.Status.UNKNOWN_CHAIN);
			return Optional.empty();
		}

		if (attach(current, placement.orElseThrow(), peer, writer) == HostedChain.Attachment.REFUSED) {
			reply.status(JoinReply.Status.FULL);
			return Optional.empty();
		}

		return placement;
	}

	/* The heads are read once the writer is attached, so an entry the chain appends in between is flooded to it and
	   leaves no gap, and off the join lock, since the read waits for the monitor of the chain, which a stream that
	   floods forged entries can hold, and every other join of the node would queue behind it. The reply carries
	   every head and a packet holds only so many, so a chain with more takes no clients. */
	private JoinReply admitted(final HostedChain hosted, final Channel peer, final WriterId writer,
		final JoinReply.JoinReplyBuilder reply) {

		final Map<WriterId, Head> heads = hosted.getChain().heads();

		if (heads.size() > ChainCodecs.MAX_HEADS_PER_PACKET) {
			hosted.detach(writer, peer);
			return reply.status(JoinReply.Status.FULL).build();
		}

		awaitClose(peer);

		return reply.status(JoinReply.Status.JOINED).chainId(hosted.getChain().id()).heads(heads).build();
	}

	/* A stream that is not open is no place to attach: its close has run or is running, and the attachment would
	   outlive it. A stream closes on the event loop that reads its packets, so it cannot close during a join. */
	private HostedChain.Attachment attach(final Hosting current, final HostedChain hosted, final Channel peer,
		final WriterId writer) {

		if (!peer.isActive()) {
			return HostedChain.Attachment.REFUSED;
		}

		final boolean streamHasRoom = current.clientsOn(peer) < MAX_WRITERS_PER_STREAM;

		return hosted.attach(writer, peer, current.settings().orElseThrow().clientsPerChain(), streamHasRoom);
	}

	private void awaitClose(final Channel peer) {
		if (peer.attr(CLOSE_AWAITED).setIfAbsent(Boolean.TRUE) == null) {
			peer.closeFuture().addListener(closed -> hosting.detachAll(peer));
		}
	}

	/* Duplicates and entries of chains this node neither hosts nor follows are dropped without a word, like
	   everything else a chain turns away: only a writer that is told the chain is full has something to do about it.
	   An entry from a stream that may not write it is dropped before anything is verified. What the chains
	   appended is flooded on and offered to the entry listeners, once per chain for the whole packet, and what
	   they refused is not. That is done on the thread of the stream, which holds no lock of the host, before this
	   returns, unless another thread is draining the chain: that thread does it when it is through with the delivery
	   it is on. The refusals come in the order of the packet, whatever chains it names. A packet with more entries to
	   verify than the stream has left of its second is dropped whole, before anything is verified. */
	@Override
	public List<EntryRefused> receive(final Channel peer, final ChainEntry entries) {
		final List<Entry> packet = Objects.requireNonNullElse(entries.getEntries(), List.<Entry>of());
		final Map<HostedChain, List<Entry>> permitted = new LinkedHashMap<>();
		final Set<Entry> full = Collections.newSetFromMap(new IdentityHashMap<>());
		final List<Runnable> steps = new ArrayList<>();

		for (final Entry entry : packet) {
			hosted(entry.header().chainId()).filter(chain -> chain.accepts(peer, entry.header().writer()))
				.ifPresent(chain -> permitted.computeIfAbsent(chain, key -> new ArrayList<>()).add(entry));
		}

		final int toVerify = permitted.values().stream().mapToInt(List::size).sum();

		if (!budgets.take(peer, StreamBudgets.Kind.ENTRIES, toVerify)) {
			return List.of();
		}

		steps.add(() -> permitted.forEach((hosted, ofChain) -> full.addAll(takeIn(hosted, peer, ofChain))));
		permitted.keySet().forEach(hosted -> steps.add(() -> drain(hosted)));
		allOf(steps);

		return packet.stream().filter(full::contains).map(entry -> EntryRefused.of(entry, Admission.FULL)).toList();
	}

	/* Runs every step, whatever an earlier one throws, and throws the first failure with the later ones on it. So the
	   chains after one whose listener fails still get their entries passed on, and a failing drain does not hide the
	   failure of the offers before it. */
	private static void allOf(final List<Runnable> steps) {
		Throwable first = null;

		for (final Runnable step : steps) {
			try {
				step.run();
			} catch (RuntimeException | Error ex) {
				if (first == null) {
					first = ex;
				} else {
					suppress(first, ex);
				}
			}
		}

		if (first instanceof RuntimeException failure) {
			throw failure;
		}

		if (first instanceof Error failure) {
			throw failure;
		}
	}

	/* A preallocated exception can be thrown by two steps, and a throwable cannot suppress itself */
	private static void suppress(final Throwable into, final Throwable failure) {
		if (into != failure) {
			into.addSuppressed(failure);
		}
	}

	/* Entries a member passes on before any member floods them. Only a member of the group of a chain this node hosts
	   is heard, as a client passes nothing on, and only for as many entries as its stream has left of its second,
	   counted as those of a flood are. Each entry is checked as a flood would be, and only one the chain would take, or
	   hold for a gap before it, goes on: of a writer this member diffuses, it is flooded, and of one it relays, it
	   is held in the embargo and passed on to the successor of its writer, never back to the stream it came on. One
	   the embargo holds already has come round the stem, and is flooded at once, which ends the round and tells
	   nothing of the exit, as a member that relayed it is never its author; its author leaves it to the shorter timers
	   of the relays that hold it, and so does a relay it came straight back to. A round moves the writer off its
	   route at the author and at a relay alike. What the chain had no room for is answered as for a flood. */
	@Override
	public List<EntryRefused> stem(final Channel peer, final ChainStem stem) {
		final List<Entry> packet = Objects.requireNonNullElse(stem.getEntries(), List.<Entry>of());
		final Map<HostedChain, List<Entry>> permitted = new LinkedHashMap<>();
		final Set<Entry> full = Collections.newSetFromMap(new IdentityHashMap<>());
		final List<Runnable> steps = new ArrayList<>();

		for (final Entry entry : packet) {
			Optional.ofNullable(entry.header().chainId()).flatMap(hosting::find)
				.filter(chain -> chain.trusts(peer))
				.ifPresent(chain -> permitted.computeIfAbsent(chain, key -> new ArrayList<>()).add(entry));
		}

		final int toVerify = permitted.values().stream().mapToInt(List::size).sum();

		if (!budgets.take(peer, StreamBudgets.Kind.ENTRIES, toVerify)) {
			return List.of();
		}

		steps.add(() -> permitted.forEach((hosted, ofChain) -> full.addAll(stems.passOn(hosted, peer, ofChain))));
		permitted.keySet().forEach(hosted -> steps.add(() -> drain(hosted)));
		allOf(steps);

		return packet.stream().filter(full::contains).map(entry -> EntryRefused.of(entry, Admission.FULL)).toList();
	}

	/* Answers the entries the chain had no room for */
	private List<Entry> takeIn(final HostedChain hosted, final Channel peer, final List<Entry> entries) {
		final List<Optional<Offer>> offers = offerAndQueue(hosted, entries, peer, true);

		return IntStream.range(0, entries.size()).filter(place -> isFull(offers.get(place)))
			.mapToObj(entries::get).toList();
	}

	private static boolean isFull(final Optional<Offer> offer) {
		return offer.isPresent() && offer.get().admission() == Admission.FULL;
	}

	/* The offers and the queueing of what they appended are one step under the delivery lock of the chain, which is
	   what makes the queue hold the deliveries in the order the chain appended them, however many streams relay the
	   same feed at once. Nothing else runs under the lock: flooding and the listeners are left to whoever drains the
	   queue, with nothing locked. What was appended before an offer failed is queued all the same. */
	@Override
	public List<Optional<Offer>> offerAndQueue(final HostedChain hosted, final List<Entry> entries,
		final Channel origin, final boolean tell) {

		final List<Optional<Offer>> offers = new ArrayList<>();
		final List<Entry> appended = new ArrayList<>();
		boolean beginsFullEpisode = false;

		try {
			synchronized (hosted.getDeliveryLock()) {
				try {
					for (final Entry entry : entries) {
						final Optional<Offer> offer = hosted.offer(entry);

						offers.add(offer);
						offer.ifPresent(taken -> noted(hosted, entry, taken, origin, appended));
					}
				} finally {
					beginsFullEpisode = queue(hosted, new Delivery(List.copyOf(appended), origin, tell));
				}
			}
		} finally {
			if (beginsFullEpisode) {
				log.warn("A chain delivery queue is full");
			}
		}

		return offers;
	}

	/* What the chain appended is queued and noted by the chain, which notes a copy of the head of a feed too */
	private static void noted(final HostedChain hosted, final Entry entry, final Offer offer, final Channel origin,
		final List<Entry> appended) {

		offer.appended().forEach(added -> {
			appended.add(added);
			hosted.appended(added, origin);
		});

		if (offer.admission() == Admission.DUPLICATE) {
			hosted.duplicated(entry, origin);
		}
	}

	/* A packet that appended nothing has nothing to deliver and takes no room in the queue. A delivery the queue has
	   no room for is dropped, the entries being in the chain all the same, but their flood is lost with it. Answers
	   whether that is the first drop of a stretch in which the queue is full, as the log is told then, and once the
	   delivery lock is released, since an appender may block. The line does not name the chain, as it would fill the
	   log of a node whose listener is stuck. */
	private boolean queue(final HostedChain hosted, final Delivery delivery) {
		final DeliveryQueue deliveries = hosted.getDeliveries();

		return !delivery.entries().isEmpty() && !deliveries.add(delivery) && deliveries.beginsFullEpisode();
	}

	/* Floods and tells the listeners what the chain appended, in the order it appended it, on this thread unless
	   another is doing so. That thread takes the deliveries queued meanwhile, each after the one it is on, so a post
	   from a listener is flooded when the listener returns. */
	private void drain(final HostedChain hosted) {
		hosted.getDeliveries().drain(delivery -> deliver(hosted, delivery));
	}

	/* Floods first, so that no other node waits for what the listeners do. A followed chain passes on nothing it took
	   in from the members, as they flood to each other, but what this node writes itself goes out on every stream the
	   chain is followed through. */
	private void deliver(final HostedChain hosted, final Delivery delivery) {
		if (hosted.isRelaying() || delivery.origin() == null) {
			flood(hosted, delivery.origin(), delivery.entries());
		}

		if (delivery.tell()) {
			delivery.entries().forEach(entry -> entryListeners.tell(listener ->
				listener.appended(hosted.getChain().id(), entry)));
		}
	}

	/* An entry this node writes itself goes in like any other, but there is no stream to spare in the flood. A
	   writer the chain takes no more of is told FULL, like an entry without room. */
	public Admission post(final Entry entry) {
		return hosted(entry.header().chainId()).map(hosted -> post(hosted, entry)).orElse(Admission.WRONG_CHAIN);
	}

	/* The entries this node writes itself are queued like the others, so that they are flooded in the order the chain
	   appended them, but the listeners are not told, as they are the sessions' own output. The caller holds locks of
	   its own, which no listener may run under, so the draining is handed to the delivery executor and the post
	   answers at once. An entry the queue has no room for is in the chain and still answers ACCEPTED, but its flood is
	   lost with the dropped delivery: members repair that through reconcile, attached clients do not. */
	private Admission post(final HostedChain hosted, final Entry entry) {
		try {
			return offerAndQueue(hosted, List.of(entry), null, false).get(0).map(Offer::admission)
				.orElse(Admission.FULL);
		} finally {
			handOver(hosted);
		}
	}

	/* An entry of this node's own that is stemmed rather than flooded, as an exit does with every entry of its
	   sessions. It is checked against the replica as an offer would be, counting what waits in the stem, and held in
	   the embargo until it comes back by flood: only then does the chain append it, report it in its heads and serve it
	   to a fetch. It goes to the successor drawn for its writer, never to one of the streams to avoid, and
	   waits in the embargo while there is none. A writer the chain takes no more of is told FULL, as by post. */
	public Admission postStem(final ChainId chainId, final Entry entry, final Set<Channel> avoid) {
		return postStem(chainId, entry, () -> avoid);
	}

	/* The same for a writer whose streams to avoid may grow, as an exit learns of the second member its client
	   attached through: they are asked again whenever an entry of the writer is stemmed, a refused one too */
	public Admission postStem(final ChainId chainId, final Entry entry, final Supplier<Set<Channel>> avoid) {
		return stems.postStem(chainId, entry, avoid);
	}

	/* Whether a writer whose entries go out through the stem has no successor left on the chain: each it was given
	   refused, went round or went away, and no member may be drawn in their place. A session of the exit that writes
	   as one of this node's own ends then, rather than have this node flood what it writes, which would name it. */
	public boolean isStranded(final ChainId chainId, final WriterId writer) {
		return stems.isStranded(chainId, writer);
	}

	/* Whether an entry posted to the stem now would find a successor outside the streams to avoid */
	public boolean canStem(final ChainId chainId, final Set<Channel> avoid) {
		return stems.canStem(chainId, avoid);
	}

	/* Nothing is drained once the host is stopped, as its executor then takes no more */
	@Override
	public void handOver(final HostedChain hosted) {
		try {
			deliveryExecutor.execute(() -> drain(hosted));
		} catch (RejectedExecutionException ex) {
			log.debug("The host is stopped, so a post is not flooded");
		}
	}

	/* The stream the entries came in on already has them, and a closed one cannot take them. A stream with no room
	   is skipped too, since writes it does not take pile up in memory: a member catches up through reconcile and a
	   client's gap expires like any other */
	private void flood(final HostedChain hosted, final Channel origin, final List<Entry> appended) {
		final List<ChainEntry> batches = ChainCodecs.batches(appended);

		if (batches.isEmpty()) {
			return;
		}

		hosted.audience().filter(stream -> stream != origin && stream.isActive() && stream.isWritable())
			.forEach(stream -> write(stream, batches));
	}

	/* One stream that fails must not stop the others, and only the class of its failure is logged, as a
	   message may name an address */
	private void write(final Channel stream, final List<ChainEntry> batches) {
		try {
			batches.forEach(stream::write);
			stream.flush();
		} catch (RuntimeException ex) {
			log.warn("The flood to a stream failed with {}", ex.getClass().getName());
		}
	}

	/* Run once a second: keeps the entries of every held chain within its retention, tells the listeners of the
	   gaps that have grown too old to fill and forgets the feeds that ended before the retention window. A gap that
	   nobody fills leaves a feed stuck, which the listeners break by dropping the sessions it belongs to. Every held
	   chain is maintained, retired ones included, since a lagging member still reads them, and followed ones, whose
	   gaps stall the sessions of a client all the same and whose writers come and go as much. */
	public void maintain() {
		Stream.concat(hosting.all(), followed.all()).forEach(this::upkeep);
	}

	/* One chain that fails is logged by the class of the failure and does not keep the others from being kept up */
	private void upkeep(final HostedChain hosted) {
		try {
			final Chain chain = hosted.getChain();

			chain.trim();
			chain.expireGaps().forEach(gap -> gapListeners.tell(listener -> listener.gapped(chain.id(), gap)));
			hosted.forgetEnded(clock.getAsLong() - chain.limits().retentionMillis());
			floodDue(hosted);
		} catch (RuntimeException ex) {
			log.warn("The upkeep of a chain failed with {}", ex.getClass().getName());
		}
	}

	/* What the embargo held past its deadline, passed on and not back, or held as its route is the stream it came in
	   on, is flooded as if it came in now, and told to the listeners like any entry the chain appends: those of this
	   node's own writers they know as such. An entry of this node's own that the chain has no room for stays in the
	   embargo and is flooded again at its next deadline; one the chain will never take goes. What never left is
	   flooded by neither the author nor a relay: it goes to the route of its writer now, once the writer was moved
	   off a route that stalled, and waits on otherwise. Only with no successor left does a relay flood it, while the
	   author drops it and the session of the writer ends, as the first to flood it would be its author. */
	private void floodDue(final HostedChain hosted) {
		final long now = clock.getAsLong();
		final List<StemEmbargo.Due> waited = hosted.getEmbargo().waited(now);
		final List<Entry> due = new ArrayList<>(waited.stream().filter(held -> held.sent() || held.bySource())
			.map(StemEmbargo.Due::entry).toList());

		waited.stream().filter(held -> !held.sent() && !held.bySource())
			.collect(Collectors.groupingBy(held -> Stem.laneOf(hosted, held.entry()), LinkedHashMap::new,
				Collectors.toList()))
			.forEach((lane, unsent) -> due.addAll(passLate(hosted, lane, unsent, now)));

		if (!due.isEmpty()) {
			final List<Optional<Offer>> offers = offerAndQueue(hosted, due, null, true);

			IntStream.range(0, due.size()).filter(place -> !isFull(offers.get(place)))
				.forEach(place -> hosted.getEmbargo().release(due.get(place)));
			handOver(hosted);
		}
	}

	/* What never left of a writer at its deadline goes to the route of the writer, once the writer was moved off a
	   route that stalled, and answers what a relay floods for want of a successor. A route counts as stalled for an
	   entry tried on it when it took no writes since the entry first missed it, or when the entry has now missed
	   MISSED_DEADLINES deadlines on it, its write there on its way or not. An entry whose write to the route it stays
	   on is still on its way waits on, and is not written there again. Nothing moves a writer a relay routes by the
	   hash, so it has no successor once its route stalled or takes no writes, and the relay floods what waits. */
	private List<Entry> passLate(final HostedChain hosted, final Stem.Lane lane, final List<StemEmbargo.Due> unsent,
		final long now) {

		final List<Entry> entries = unsent.stream().map(StemEmbargo.Due::entry).toList();
		final boolean hashed = lane.isHashed(hosted);
		final Optional<Channel> route = lane.isLost(hosted) ? Optional.empty() : hosted.getRouter()
			.moveIfStalled(lane.writer(), lane.avoid(hosted), current -> stalls(current, unsent))
			.filter(current -> !hashed || current.isWritable() && !stalls(current, unsent));

		if (route.isPresent()) {
			passAgain(hosted, route.get(), unsent, now);
			return List.of();
		}

		if (unsent.get(0).own()) {
			entries.forEach(hosted.getEmbargo()::release);
			return List.of();
		}

		return entries;
	}

	/* Passes on what is due, but for what was written to the route already and is on its way, which waits on */
	private void passAgain(final HostedChain hosted, final Channel route, final List<StemEmbargo.Due> unsent,
		final long now) {

		final List<Entry> again = unsent.stream().filter(held -> held.waiting() == null
			|| !held.waiting().isPending(route)).map(StemEmbargo.Due::entry).toList();

		if (!again.isEmpty()) {
			stems.pass(hosted, route, again, now);
		}
	}

	private static boolean stalls(final Channel route, final List<StemEmbargo.Due> unsent) {
		return unsent.stream().map(StemEmbargo.Due::waiting).filter(Objects::nonNull)
			.anyMatch(waiting -> waiting.route() == route && (StemStalls.since(route, waiting.since())
				|| waiting.missed() >= StemEmbargo.MISSED_DEADLINES));
	}

	/* Drops the retired chains that are done, by the spork as it reads now. While it is on, a chain goes once its
	   grace is over, after every drain was told while the chain is still hosted, so that the Close of each session a
	   drain ends is flooded on; a drain that failed is told again at the next tick. It goes no sooner, whether anything
	   runs on it here or not, as an exit stems the entries of a session that drains on it to any member of the group.
	   A spork that is absent or cannot be read means the proxy is off, as it does to the client sessions: no grace
	   runs out and no drain is told, and a chain nothing runs on, with no client attached to it here and no session of
	   this node on it, goes once it has been retired for the retention window the hosting was built with, as a node
	   whose clock runs a little behind still writes to it for a moment.

	   No lock of this host is held while a drain is asked or told: a drain that ends its sessions posts into this host
	   under locks of its own (its relay's, its session's and its outbox's, then the delivery lock of the chain), and
	   the lock of refresh is taken under none of them. It is taken for the swap alone. */
	public void drain() {
		final Hosting previous = hosting;

		if (previous.retired().isEmpty()) {
			return;
		}

		final Optional<ProxySettings> spork = sporkNow();

		previous.settings().ifPresent(built -> dropDone(previous, spork, spork.orElse(built), clock.getAsLong()));
	}

	/* A spork that cannot be read fails the refresh of the same tick, which logs it */
	private Optional<ProxySettings> sporkNow() {
		try {
			return settings.current();
		} catch (RuntimeException ex) {
			return Optional.empty();
		}
	}

	private void dropDone(final Hosting previous, final Optional<ProxySettings> spork, final ProxySettings proxy,
		final long now) {

		final Set<ChainId> drop = new LinkedHashSet<>();

		if (spork.isPresent()) {
			drop.addAll(expire(previous, graceMillis(spork.get()), now));
		} else {
			previous.retiredFor(now, TimeUnit.SECONDS.toMillis(proxy.retentionSeconds())).stream()
				.filter(id -> !isInUse(previous.retired().get(id), id)).forEach(drop::add);
		}

		swapOut(previous, drop);
	}

	/* Tells the drains of every chain whose grace is over and answers the chains that go: those whose drains all
	   returned, and those retired for GRACES_UNTIL_DROPPED graces whatever their drains did */
	private Set<ChainId> expire(final Hosting previous, final long grace, final long now) {
		final Set<ChainId> expired = new LinkedHashSet<>(previous.retiredFor(now, GRACES_UNTIL_DROPPED * grace));

		for (final ChainId id : previous.retiredFor(now, grace)) {
			if (drains.tellEach(draining -> draining.expired(id))) {
				expired.add(id);
			}
		}

		return expired;
	}

	/* Under the lock of refresh, so the swap never falls between the read and the write of one. A refresh that
	   replaced the hosting meanwhile may have taken a chain back or retired it anew, so the drop is left to the next
	   tick, which decides again on the hosting that refresh built. */
	private synchronized void swapOut(final Hosting previous, final Set<ChainId> drop) {
		if (hosting == previous && !drop.isEmpty()) {
			hosting = previous.without(drop);
		}
	}

	/* A client attached to the chain, or a session of this node on it, is what a chain past the retention window is
	   kept for while the proxy is off */
	private boolean isInUse(final HostedChain hosted, final ChainId id) {
		return hosted.clientCount() > 0 || drains.any(draining -> draining.holds(id));
	}

	/* Run once a second: each chain with entries tells one random member how far it has got, so a member that
	   is behind asks for what it lacks. Those entries come back through receive like any other. Only streams that
	   are open and take writes are chosen from, and a client is none of them: it never needs another's entries. */
	public void reconcileRound() {
		hosting.current().values().forEach(this::announce);
	}

	private void announce(final HostedChain hosted) {
		final List<Channel> members = hosted.getMemberStreams().stream().filter(Channel::isActive)
			.filter(Channel::isWritable).toList();
		final Map<WriterId, Head> heads = hosted.getChain().heads();

		if (members.isEmpty() || heads.isEmpty()) {
			return;
		}

		final Channel member = members.get(random.nextInt(members.size()));
		final ChainHeads announcement = ChainHeads.builder().chainId(hosted.getChain().id()).heads(capped(heads))
			.build();

		try {
			member.writeAndFlush(announcement);
		} catch (RuntimeException ex) {
			log.warn("The heads to a member stream failed with {}", ex.getClass().getName());
		}
	}

	/* A packet of heads holds only so many, and the writers a chain takes are bounded by the same number, so this
	   only matters to a chain that was handed more feeds than it takes. It announces a part of them. */
	private static Map<WriterId, Head> capped(final Map<WriterId, Head> heads) {
		if (heads.size() <= ChainCodecs.MAX_HEADS_PER_PACKET) {
			return heads;
		}

		return heads.entrySet().stream().limit(ChainCodecs.MAX_HEADS_PER_PACKET)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/* The heads of a peer only say what to ask for: nothing is adopted from them, and no feed is anchored at a
	   head a peer names (follow is the one exception, and only for the member a client chose), so a peer cannot pin
	   a writer to a head that is not its own. A feed this chain has not
	   seen starts at its first entry, and if that entry is gone the entries that follow it wait until the gap
	   expires. Only a member is answered, and only while its stream takes writes, as answers it does not read would
	   pile up in memory, and only so many of its packets of heads in a second, counted before the heads of the chain
	   are read. */
	@Override
	public Optional<FetchEntries> reconcile(final Channel peer, final ChainHeads heads) {
		return hosted(heads.getChainId()).filter(hosted -> hosted.trusts(peer))
			.filter(hosted -> peer.isWritable())
			.filter(hosted -> budgets.take(peer, StreamBudgets.Kind.HEADS, 1))
			.map(hosted -> missing(hosted, heads.getHeads())).filter(ranges -> !ranges.isEmpty())
			.map(ranges -> FetchEntries.builder().chainId(heads.getChainId()).ranges(ranges).build());
	}

	/* The writers this chain is behind the peer on and can take, by ascending id. A feed whose entries are gone at
	   every member is claimed again in every round, so asking for the lowest ids each time would never reach a
	   writer that sorts after eight of them: with more than eight, the eight asked for start at a random place of
	   the list and carry on from its start when the end is passed. */
	private List<FetchEntries.Range> missing(final HostedChain hosted, final Map<WriterId, Head> remote) {
		final List<FetchEntries.Range> ahead = HeadsDiff.missing(hosted.getChain().heads(), remote, RANGE_ENTRIES)
			.stream().filter(range -> hosted.canTake(range.writer()))
			.map(range -> new FetchEntries.Range(range.writer(), range.fromSeq(), range.toSeq())).toList();

		if (ahead.size() <= MAX_RANGES) {
			return ahead;
		}

		final int start = random.nextInt(ahead.size());

		return IntStream.range(0, MAX_RANGES).mapToObj(place -> ahead.get((start + place) % ahead.size())).toList();
	}

	/* The answers to a member that never reads would pile up in the buffer of its stream, which only turns unwritable
	   when it is full, so a stream that takes no writes gets no answer and asks again. The ranges are charged before
	   the answer is built, which waits for the monitor of the chain, and the bytes of the answer once it is built:
	   an answer that does not fit what is left of the second is not sent, and the member asks again later. */
	@Override
	public List<Entry> fetch(final Channel peer, final FetchEntries request) {
		return hosted(request.getChainId()).filter(hosted -> hosted.trusts(peer))
			.filter(hosted -> peer.isActive() && peer.isWritable())
			.filter(hosted -> budgets.take(peer, StreamBudgets.Kind.RANGES,
				Math.min(request.getRanges().size(), MAX_RANGES)))
			.map(hosted -> withinBudget(peer, hosted.getChain(), request.getRanges())).orElse(List.of());
	}

	private List<Entry> withinBudget(final Channel peer, final Chain chain, final List<FetchEntries.Range> ranges) {
		final List<Entry> answer = retained(chain, ranges);
		final int bytes = answer.stream().mapToInt(ChainCodecs::encodedBytes).sum();

		return budgets.take(peer, StreamBudgets.Kind.FETCH_BYTES, bytes) ? answer : List.of();
	}

	/* A range asked for twice is answered once, and a range wider than a packet is answered with its first entries */
	private static List<Entry> retained(final Chain chain, final List<FetchEntries.Range> ranges) {
		final List<Entry> oldestFirst = ranges.stream().limit(MAX_RANGES)
			.flatMap(range -> chain.range(range.writer(), range.fromSeq(),
				range.fromSeq() + Math.min(range.toSeq() - range.fromSeq(), RANGE_ENTRIES - 1)).stream())
			.distinct().sorted(OLDEST_FIRST).toList();

		return withinBytes(oldestFirst);
	}

	/* One answer must fit the frames the requester accepts, so it stops at the entry that would pass the cap and
	   the requester asks for the rest next round */
	private static List<Entry> withinBytes(final List<Entry> entries) {
		final List<Entry> within = new ArrayList<>();
		long bytes = 0;

		for (final Entry entry : entries) {
			bytes += ChainCodecs.encodedBytes(entry);

			if (bytes > ChainCodecs.MAX_FRAME_BYTES) {
				break;
			}

			within.add(entry);
		}

		return within;
	}

	/* Only a full chain asks the writer to wait, and only a member of that chain's group is believed, on a retired
	   chain as on a current one since both still take entries, and on a followed chain a member it is followed
	   through. A refusal does not say whether the writer is one of this node's, so whatever owns a writer looks its
	   pause up here. A member that refused an entry of a writer stemmed to it gets nothing more of the writer, and
	   what the embargo holds of the writer from the refused entry on, of what went to that member, goes to
	   the other successor at once, unless that is a member one of those entries came from: an author and a relay do so
	   alike, as both hold what they passed on, and neither ever passes an entry back to where it came from. */
	@Override
	public void refused(final Channel peer, final EntryRefused refusal) {
		if (refusal != null && refusal.getWriter() != null && refusal.getAdmission() == Admission.FULL
			&& hosted(refusal.getChainId()).filter(hosted -> hosted.trusts(peer)).isPresent()) {

			pauses.pause(refusal);
			hosting.find(refusal.getChainId()).ifPresent(hosted -> stemAgain(hosted, refusal, peer,
				clock.getAsLong()));
		}
	}

	private void stemAgain(final HostedChain hosted, final EntryRefused refusal, final Channel refuser,
		final long now) {

		final StemEmbargo embargo = hosted.getEmbargo();
		final List<Entry> refused = embargo.from(refusal.getWriter(), refusal.getSeq()).stream()
			.filter(entry -> embargo.wasSentTo(entry, refuser)).toList();

		/* A refusal of nothing that went to the member through the stem, as of a copy of a flood or one sent
		   unasked, neither bars it nor spends the move of the writer. Of what moves on, an entry that came from the
		   successor it moves to waits in the embargo. */
		if (!refused.isEmpty()) {
			hosted.getRouter().refused(refusal.getWriter(), refuser, hosted.avoidOf(refusal.getWriter()))
				.ifPresent(next -> stems.pass(hosted, next, refused, now));
		}
	}

	public WriterPauses pauses() {
		return pauses;
	}

	/* Offers the entries of a writer of this node again, from the lowest sequence number a member refused, to every
	   member and attached client, and lets the writer go on. Whoever has them already drops the duplicates without
	   a word, so nothing is amplified. The pause is lifted before they go out, so that a refusal they draw pauses the
	   writer again, and it is lifted for a chain that is gone all the same, as nothing else would ever lift it. */
	public void resend(final ChainId chainId, final WriterId writer) {
		pauses.refusedFrom(chainId, writer).ifPresent(seq -> {
			pauses.resume(chainId, writer);
			hosted(chainId).ifPresent(hosted -> queueAgain(hosted, writer, seq));
		});
	}

	/* Lets a writer whose entries go out through the stem go on after a refusal: what was refused went on to the
	   other successor when the refusal came, as at a relay, and what came back already is left to reconcile, as
	   flooding it again would show the member that refused it whose it is */
	public void resume(final ChainId chainId, final WriterId writer) {
		pauses.resume(chainId, writer);
	}

	/* The successors this node drew for a writer on a chain, for a test */
	List<Channel> stemSuccessors(final ChainId chainId, final WriterId writer) {
		return hosting.find(chainId).map(hosted -> hosted.getRouter().drawn(writer)).orElse(List.of());
	}

	/* The chain holds the entries already, so they are queued without an offer, and go out like a post: in the order
	   of the queue, by the delivery executor, to every stream, and told to no listener */
	private void queueAgain(final HostedChain hosted, final WriterId writer, final long seq) {
		final boolean beginsFullEpisode;

		synchronized (hosted.getDeliveryLock()) {
			final List<Entry> retained = hosted.getChain().range(writer, seq, Long.MAX_VALUE);

			beginsFullEpisode = queue(hosted, new Delivery(retained, null, false));
		}

		if (beginsFullEpisode) {
			log.warn("A chain delivery queue is full");
		}

		handOver(hosted);
	}

	/* A leave is unsigned, so it is only believed from the stream the writer attached through */
	@Override
	public void leave(final Channel peer, final LeaveChain leave) {
		if (leave.getChainId() != null && leave.getWriter() != null) {
			hosted(leave.getChainId()).ifPresent(hosted -> hosted.detach(leave.getWriter(), peer));
		}
	}
}
