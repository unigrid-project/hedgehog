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

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
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

/* Hosts the chains of this node's shard group while a valid communication spork says the proxy is on. Ticks on a
   scheduler of its own, so a spork that changes while nothing else happens still starts and stops the hosting. */
@Slf4j
@Eager @ApplicationScoped
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true) /* The proxy of a normal scoped bean needs it */
public class ChainHost implements ChainService {
	/* A client keeps one writer on a chain, and a few chains at most, so more is a stream hoarding places */
	static final int MAX_WRITERS_PER_STREAM = 4;

	/* A chain this node stopped hosting is kept for the sessions that still run on it and the members that lag
	   while the epoch it was created for is at most this many epochs behind the current one, and dropped when
	   it is more behind. It is the epoch of the chain that counts, not how often the topology made the node
	   rebuild its hosting since. */
	static final int RETIRED_EPOCHS = 4;

	/* The most retired chains held, whatever their age, as a node must not end up holding every chain it ever
	   hosted: the oldest epoch goes first, and within it the chain that retired first. Maintenance trims a retired
	   chain every second, so an idle one holds next to nothing, but the count is bounded all the same. */
	static final int MAX_RETIRED_CHAINS = 1024;

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
	private final Listeners<GapListener> gapListeners = new Listeners<>();
	private final Object joins = new Object();

	/* Volatile so that the write which swaps in a finished hosting is seen whole by the netty threads that read it
	   while the refresh thread works on the next one */
	private volatile Hosting hosting = Hosting.OFF;

	@Inject
	public ChainHost(final ProxySettingsSource settings, final GridnodeDirectory directory,
		final ChainTransport transport) {

		this(settings, directory, transport, System::currentTimeMillis, new Random(),
			Executors.newSingleThreadScheduledExecutor(ChainHost::daemonThread));
	}

	ChainHost(final ProxySettingsSource settings, final GridnodeDirectory directory, final ChainTransport transport,
		final LongSupplier clock, final RandomGenerator random, final ScheduledExecutorService scheduler) {

		this.settings = settings;
		this.directory = directory;
		this.transport = transport;
		this.clock = clock;
		this.random = random;
		this.scheduler = scheduler;
	}

	/* A daemon, so a host that is never stopped still lets the process end */
	private static Thread daemonThread(final Runnable body) {
		final Thread thread = new Thread(body, "chain-host");
		thread.setDaemon(true);
		return thread;
	}

	@PostConstruct
	void start() {
		scheduler.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
	}

	@PreDestroy
	void stop() {
		scheduler.shutdownNow();
	}

	/* One tick follows the spork, the topology and the clock, then keeps the chains in shape and only then talks to
	   the members, so that a fetch it sets going is never served from entries past the retention window. */
	public void tick() {
		guarded("refresh", this::refresh);
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

	/* A packet may name no chain, which the map of a hosting that is off cannot be asked for */
	Optional<HostedChain> hosted(final ChainId id) {
		return Optional.ofNullable(id).flatMap(hosting::find);
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

	private Hosting rebuilt(final Hosting.Basis basis, final long now, final Hosting previous) {
		final GroupView view = viewOf(basis.settings(), now, basis.activeIds());
		final ChainLimits limits = basis.settings().chainLimits();
		final OptionalInt group = basis.own().map(id -> view.groups().groupOf(id)).orElse(OptionalInt.empty());
		final Map<ChainId, HostedChain> chains = new LinkedHashMap<>();

		for (final ChainId id : basis.own().map(view::chainsOf).orElse(List.of())) {
			chains.put(id, previous.reusable(id, limits)
				.orElseGet(() -> new HostedChain(new Chain(id, limits, clock), basis.epoch())));
		}

		return new Hosting(Optional.of(basis), group, peersOf(view, group, basis.own()),
			Collections.unmodifiableMap(chains), retiredBy(previous, chains.keySet(), basis.epoch()));
	}

	/* The chains held and not wanted now are retired, in the order they retired. A retired chain that is wanted
	   again leaves them to be a current one, and one whose epoch is more than RETIRED_EPOCHS behind the current
	   epoch is dropped. An epoch ahead of the current one, as after a clock that stepped back, is not behind. This
	   runs when a hosting is rebuilt and only then, since the wanted chains and the epoch change only then: a
	   reused hosting has the retired chains it had. */
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
	   the address of the gridnode: the cost is bounded by the size of the group. The chains share one immutable
	   list, which the netty threads read through a volatile field while this thread writes the next one. A stream
	   that is not open is no stream of a member; the list is as old as the refresh, so a stream that closes
	   later is dropped by the next one. */
	private void holdStreams(final Hosting next) {
		final List<Channel> streams = next.peers().stream().map(this::lookUp).flatMap(Optional::stream)
			.filter(Channel::isActive).toList();

		next.current().values().forEach(hosted -> hosted.setMemberStreams(streams));
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
	   of refresh(), which waits for stream lookups that a join must not wait for. */
	@Override
	public JoinReply join(final Channel peer, final JoinChain request) {
		final JoinReply.JoinReplyBuilder reply = JoinReply.builder().requestId(request.getRequestId());
		final WriterId writer = request.getWriter();

		synchronized (joins) {
			final Hosting current = hosting;

			if (current.settings().isEmpty()) {
				return reply.status(JoinReply.Status.DISABLED).build();
			}

			if (writer == null) {
				return reply.status(JoinReply.Status.FULL).build();
			}

			return current.placement(writer, request.getChainId())
				.map(hosted -> admit(current, hosted, peer, writer, reply))
				.orElseGet(() -> reply.status(JoinReply.Status.UNKNOWN_CHAIN).build());
		}
	}

	private JoinReply admit(final Hosting current, final HostedChain hosted, final Channel peer,
		final WriterId writer, final JoinReply.JoinReplyBuilder reply) {

		final HostedChain.Attachment attachment = attach(current, hosted, peer, writer);

		if (attachment == HostedChain.Attachment.REFUSED) {
			return reply.status(JoinReply.Status.FULL).build();
		}

		final Map<WriterId, Head> heads = hosted.getChain().heads();

		/* The reply carries every head and a packet holds only so many, so a chain with more takes no clients */
		if (heads.size() > ChainCodecs.MAX_HEADS_PER_PACKET) {
			hosted.detach(writer, peer);
			return reply.status(JoinReply.Status.FULL).build();
		}

		if (attachment == HostedChain.Attachment.NEW) {
			awaitClose(peer);
		}

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

	/* Duplicates and entries of chains this node does not host are dropped without a word, like everything
	   else a chain turns away: only a writer that is told the chain is full has something to do about it.
	   An entry from a stream that may not write it is dropped before anything is verified. What the chains
	   appended is flooded on, once per chain for the whole packet, and what they refused is not. */
	@Override
	public List<EntryRefused> receive(final Channel peer, final ChainEntry entries) {
		final Hosting current = hosting;
		final List<EntryRefused> refusals = new ArrayList<>();
		final Map<HostedChain, List<Entry>> appended = new LinkedHashMap<>();

		for (final Entry entry : Objects.requireNonNullElse(entries.getEntries(), List.<Entry>of())) {
			final Optional<HostedChain> hosted = current.find(entry.header().chainId())
				.filter(chain -> chain.accepts(peer, entry.header().writer()));
			final Optional<Offer> offer = hosted.flatMap(chain -> chain.offer(entry));

			offer.ifPresent(taken -> appended.computeIfAbsent(hosted.get(), chain -> new ArrayList<>())
				.addAll(taken.appended()));

			if (offer.isPresent() && offer.get().admission() == Admission.FULL) {
				refusals.add(EntryRefused.of(entry, Admission.FULL));
			}
		}

		appended.forEach((hosted, taken) -> flood(hosted, peer, taken));
		return refusals;
	}

	/* An entry this node writes itself goes in like any other, but there is no stream to spare in the flood. A
	   writer the chain takes no more of is told FULL, like a sealed entry without room. */
	public Admission post(final Entry entry) {
		return hosted(entry.header().chainId()).map(hosted -> post(hosted, entry)).orElse(Admission.WRONG_CHAIN);
	}

	private Admission post(final HostedChain hosted, final Entry entry) {
		final Optional<Offer> offer = hosted.offer(entry);

		offer.ifPresent(taken -> flood(hosted, null, taken.appended()));
		return offer.map(Offer::admission).orElse(Admission.FULL);
	}

	/* The stream the entries came in on already has them, and a closed one cannot take them. A stream with no room
	   is skipped too, since writes it does not take pile up in memory: a member catches up through reconcile and a
	   client's gap expires like any other */
	private void flood(final HostedChain hosted, final Channel origin, final List<Entry> appended) {
		final List<ChainEntry> batches = ChainEntry.batches(appended);

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

	/* Run once a second: keeps the entries of every held chain within its retention and tells the listeners of the
	   gaps that have grown too old to fill. A gap that nobody fills leaves a feed stuck, which the listeners break
	   by dropping the sessions it belongs to. Every held chain is maintained, retired ones included, since a lagging
	   member still reads them. */
	public void maintain() {
		hosting.all().forEach(this::upkeep);
	}

	/* One chain that fails is logged by the class of the failure and does not keep the others from being kept up */
	private void upkeep(final HostedChain hosted) {
		try {
			hosted.getChain().trim();
			hosted.getChain().expireGaps().forEach(gap -> gapListeners.tell(listener ->
				listener.gapped(hosted.getChain().id(), gap)));
		} catch (RuntimeException ex) {
			log.warn("The upkeep of a chain failed with {}", ex.getClass().getName());
		}
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
	   head a peer names, so a peer cannot pin a writer to a head that is not its own. A feed this chain has not
	   seen starts at its first entry, and if that entry is gone the entries that follow it wait until the gap
	   expires. Only a member is answered. */
	@Override
	public Optional<FetchEntries> reconcile(final Channel peer, final ChainHeads heads) {
		return hosted(heads.getChainId()).filter(hosted -> hosted.trusts(peer))
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
	   when it is full, so a stream that takes no writes gets no answer and asks again */
	@Override
	public List<Entry> fetch(final Channel peer, final FetchEntries request) {
		return hosted(request.getChainId()).filter(hosted -> hosted.trusts(peer))
			.filter(hosted -> peer.isActive() && peer.isWritable())
			.map(hosted -> retained(hosted.getChain(), request.getRanges())).orElse(List.of());
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

	@Override
	public void refused(final Channel peer, final EntryRefused refusal) {
		/* No writer of this node is on a chain to pause */
	}

	/* A leave is unsigned, so it is only believed from the stream the writer attached through */
	@Override
	public void leave(final Channel peer, final LeaveChain leave) {
		if (leave.getChainId() != null && leave.getWriter() != null) {
			hosted(leave.getChainId()).ifPresent(hosted -> hosted.detach(leave.getWriter(), peer));
		}
	}
}
