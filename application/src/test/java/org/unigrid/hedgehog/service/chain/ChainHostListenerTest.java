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

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;

public class ChainHostListenerTest {
	private static final int TIMEOUT_SECONDS = 10;
	private static final String DETAIL = "10.1.2.3:4567";
	private static final long EPOCH_MILLIS = 3_600_000;
	/* The deliveries a chain keeps waiting for the listeners, stated here so that a change of the bound fails a test */
	private static final int PENDING_DELIVERIES = 4096;
	private static final int REPEATS = 8;
	private static final int SMALL_BOUND = 3;
	private static final int DROPPED = 100;

	private record Offered(ChainId chainId, Entry entry) {
		/* One entry a listener was offered */
	}

	/* A call on a thread of its own, so that a test can hold one stream inside a listener while another arrives.
	   Every wait is bounded, so a lock that is never released fails the test and does not hang it, and the
	   thread is a daemon, so that one that stays blocked does not keep the test process alive. */
	private static final class Task<T> {
		private final FutureTask<T> task;
		private final Thread thread;

		Task(final Callable<T> call) {
			task = new FutureTask<>(call);
			thread = new Thread(task);
			thread.setDaemon(true);
		}

		Task<T> start() {
			thread.start();
			return this;
		}

		/* A stream that waits for a lock is blocked or parked, and one that was not held back has ended */
		void awaitStopped() {
			await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).pollInterval(1, TimeUnit.MILLISECONDS)
				.until(() -> thread.getState() != Thread.State.NEW && thread.getState() != Thread.State.RUNNABLE);
		}

		Thread.State state() {
			return thread.getState();
		}

		@SneakyThrows
		T result() {
			return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
	}

	/* A client stream that holds its first write until it is released */
	private static final class GatedStream extends EmbeddedChannel {
		private final CountDownLatch writing = new CountDownLatch(1);
		private final CountDownLatch released = new CountDownLatch(1);
		private final AtomicBoolean gated = new AtomicBoolean(true);

		@Override
		public ChannelFuture write(final Object message) {
			if (gated.getAndSet(false)) {
				writing.countDown();
				awaitIn(released);
			}

			return super.write(message);
		}
	}

	@SneakyThrows
	private static void awaitIn(final CountDownLatch latch) {
		assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
	}

	private static List<Offered> listen(final ChainHost host) {
		final List<Offered> offered = new CopyOnWriteArrayList<>();

		host.addEntryListener((chainId, entry) -> offered.add(new Offered(chainId, entry)));
		return offered;
	}

	private static List<Entry> heard(final List<Offered> offered) {
		return offered.stream().map(Offered::entry).toList();
	}

	private static List<Entry> heardOn(final List<Offered> offered, final ChainId chainId) {
		return heard(offered.stream().filter(each -> each.chainId().equals(chainId)).toList());
	}

	private static List<Long> seqs(final List<Entry> entries) {
		return entries.stream().map(entry -> entry.header().seq()).toList();
	}

	private static List<Entry> entriesOf(final ChainId chainId, final int count) {
		return ChainFleet.entries(ChainFleet.key(1), chainId, count, ChainFleet.NOW);
	}

	private static ChainEntry packet(final List<Entry> entries) {
		return ChainEntry.builder().entries(entries).build();
	}

	private static void post(final ChainFleet fleet, final String member, final EmbeddedChannel stream,
		final List<Entry> entries) {

		fleet.host(member).receive(stream, packet(entries));
		fleet.deliver();
	}

	private static Task<List<EntryRefused>> relay(final ChainFleet fleet, final String member,
		final List<Entry> entries) {

		final EmbeddedChannel stream = fleet.stream("gn-0", member);

		return new Task<>(() -> fleet.host("gn-0").receive(stream, packet(entries)));
	}

	/* What was flooded to a stream, in the order it was written */
	private static List<Entry> written(final EmbeddedChannel stream) {
		return stream.outboundMessages().stream().filter(ChainEntry.class::isInstance)
			.flatMap(sent -> ((ChainEntry) sent).getEntries().stream()).toList();
	}

	/* A host of its own over clients only, whose clock follows the fleet's and whose deliveries are run by the given
	   executor */
	private static ChainHost hostWith(final ChainFleet fleet, final Executor delivery) {
		final ChainHost host = new ChainHost(fleet.settings::get,
			new TopologyGridnodeDirectory(() -> fleet.gridnodes, () -> "gn-0"), peer -> Optional.empty(),
			fleet.clock::get, fleet.random, new ManualScheduler(), delivery);

		host.refresh();
		return host;
	}

	private static EmbeddedChannel attached(final ChainHost host, final ChainId chainId, final int writer) {
		final EmbeddedChannel client = new EmbeddedChannel();

		host.join(client, JoinChain.builder().requestId(writer).writer(ChainFleet.writer(writer)).chainId(chainId)
			.build());
		return client;
	}

	private static ExecutorService daemonExecutor(final String name) {
		return Executors.newSingleThreadExecutor(body -> {
			final Thread thread = new Thread(body, name);

			thread.setDaemon(true);
			return thread;
		});
	}

	/* A host whose clock throws once it is armed, with a client attached as the writers 1 and 2 */
	private record Failing(ChainHost host, ChainId chainId, EmbeddedChannel client, AtomicInteger reads,
		AtomicInteger failAt) {

		/* Makes the offer of a probe of the writer 2, which counts the clock reads one offer takes, and arms the clock
		   to throw on the first read of the offer after the next one. Answers the probe. */
		Entry armForTheSecondOffer() {
			final Entry probe = ChainFleet.entries(ChainFleet.key(2), chainId, 1, ChainFleet.NOW).get(0);

			reads.set(0);
			host.receive(client, packet(List.of(probe)));
			failAt.set(reads.getAndSet(0));
			return probe;
		}
	}

	private static Failing failing(final ChainFleet fleet) {
		final AtomicInteger reads = new AtomicInteger();
		final AtomicInteger failAt = new AtomicInteger(-1);
		final ChainHost host = new ChainHost(fleet.settings::get,
			new TopologyGridnodeDirectory(() -> fleet.gridnodes, () -> "gn-0"), peer -> Optional.empty(), () -> {
				if (reads.getAndIncrement() == failAt.get()) {
					throw new IllegalStateException(DETAIL);
				}

				return fleet.clock.get();
			}, fleet.random, new ManualScheduler());

		host.refresh();

		final ChainId chainId = host.hostedChains().get(0);
		final EmbeddedChannel client = attached(host, chainId, 1);

		host.join(client, JoinChain.builder().requestId(2).writer(ChainFleet.writer(2)).chainId(chainId).build());
		return new Failing(host, chainId, client, reads, failAt);
	}

	/* Leaves the delivery of the second entry queued with no thread to drain it: the listener of the first holds the
	   drain while the second comes in on another thread, and then throws an Error, which ends the drain. The entries
	   are those of the writer the client is attached as. */
	private static void leaveSecondQueued(final ChainHost host, final EmbeddedChannel client,
		final List<Entry> entries) {

		final CountDownLatch inListener = new CountDownLatch(1);
		final CountDownLatch queued = new CountDownLatch(1);
		final AtomicBoolean failing = new AtomicBoolean(true);

		host.addEntryListener((chainId, entry) -> {
			if (failing.getAndSet(false)) {
				inListener.countDown();
				awaitIn(queued);
				throw new LinkageError(DETAIL);
			}
		});

		final Task<?> draining = new Task<>(() -> host.receive(client, packet(entries.subList(0, 1)))).start();

		awaitIn(inListener);
		new Task<>(() -> host.receive(client, packet(entries.subList(1, 2)))).start().result();
		queued.countDown();
		assertThat(assertThrows(ExecutionException.class, draining::result).getCause(), instanceOf(LinkageError.class));
	}

	/* What the listener had heard when the other relays had returned and the action had run, and what it heard once
	   the first was let go */
	private record Relayed(List<Entry> heardWhileHeld, List<Entry> heard) {
		/* The two moments of one listener */
	}

	/* Three members relay one feed, an entry each. The relay of the first is held in the listener of its entry, and
	   the other two have to come back without waiting for it, the action running when they have. */
	private static Relayed relayedAtOnce(final ChainFleet fleet, final List<Entry> entries,
		final Runnable whileHeld) {

		final List<Entry> heard = new CopyOnWriteArrayList<>();
		final CountDownLatch inFirstListener = new CountDownLatch(1);
		final CountDownLatch letGo = new CountDownLatch(1);

		fleet.host("gn-0").addEntryListener((chainId, entry) -> {
			if (entry.equals(entries.get(0))) {
				inFirstListener.countDown();
				awaitIn(letGo);
			}

			heard.add(entry);
		});

		final Task<?> first = relay(fleet, "gn-1", entries.subList(0, 1)).start();

		awaitIn(inFirstListener);
		relay(fleet, "gn-2", entries.subList(1, 2)).start().result();
		relay(fleet, "gn-3", entries.subList(2, 3)).start().result();
		whileHeld.run();

		final List<Entry> heardWhileHeld = List.copyOf(heard);

		letGo.countDown();
		first.result();
		return new Relayed(heardWhileHeld, List.copyOf(heard));
	}

	/* A chain whose listener is held on the first entry of a feed, while the entries after it, a delivery each, fill
	   the queue of the chain to its bound. The feed goes on for as many entries as the test names. */
	private record Backlog(ChainFleet fleet, ChainHost host, List<Entry> entries, EmbeddedChannel stream,
		List<Offered> offered, CountDownLatch letGo, Task<?> draining) {

		/* The relay of the entry at the index by a member, on a thread of its own. It comes in a second of its
		   own, as one stream may only send so many entries a second and the fill of the queue takes them all */
		Task<List<EntryRefused>> relayed(final int index) {
			return new Task<>(() -> {
				fleet.clock.addAndGet(1000);
				return host.receive(stream, packet(entries.subList(index, index + 1)));
			});
		}

		/* The relay of the entries from the first index up to but not including the last, one packet each */
		Task<Object> relayedAll(final int from, final int to) {
			return new Task<>(() -> {
				fleet.clock.addAndGet(1000);
				entries.subList(from, to).forEach(entry -> host.receive(stream, packet(List.of(entry))));
				return null;
			});
		}
	}

	/* The queue of the chain is the one the host makes, which keeps as many deliveries as the bound of the host says.
	   The feed has two more entries: one that does not fit, and one for later. */
	private static Backlog backlog() {
		return filled(ChainFleet.group(), PENDING_DELIVERIES, 2);
	}

	/* The queue of the chain keeps as many deliveries as the bound given, which keeps a test short */
	private static Backlog smallBacklog(final int bound, final int more) {
		final ChainFleet fleet = ChainFleet.group();

		fleet.host("gn-0").hosted(fleet.chain()).orElseThrow()
			.setDeliveries(new DeliveryQueue(new LinkedBlockingQueue<>(bound)));
		return filled(fleet, bound, more);
	}

	/* The entry in the listener is relayed a few times more first: a packet that appended nothing takes no room */
	private static Backlog filled(final ChainFleet fleet, final int waiting, final int more) {
		final ChainHost host = fleet.host("gn-0");
		final List<Entry> entries = entriesOf(fleet.chain(), 1 + waiting + more);
		final EmbeddedChannel stream = fleet.stream("gn-0", "gn-2");
		final CountDownLatch inListener = new CountDownLatch(1);
		final CountDownLatch letGo = new CountDownLatch(1);
		final List<Offered> offered = new CopyOnWriteArrayList<>();

		host.addEntryListener((chainId, entry) -> {
			if (entry.equals(entries.get(0))) {
				inListener.countDown();
				awaitIn(letGo);
			}

			offered.add(new Offered(chainId, entry));
		});

		final Task<?> draining = relay(fleet, "gn-1", entries.subList(0, 1)).start();

		awaitIn(inListener);
		new Task<>(() -> {
			IntStream.range(0, REPEATS).forEach(repeat -> host.receive(stream, packet(entries.subList(0, 1))));
			return null;
		}).start().result();

		final Backlog backlog = new Backlog(fleet, host, entries, stream, offered, letGo, draining);

		backlog.relayedAll(1, waiting + 1).start().result();
		return backlog;
	}

	/* How one entry of a feed reaches the host, whether another member relays it or this node writes it itself */
	private interface Source {
		Callable<Object> of(ChainFleet fleet, List<Entry> entries);
	}

	private static Source relayedBy(final String member, final int index) {
		return (fleet, entries) -> () -> fleet.host("gn-0").receive(fleet.stream("gn-0", member),
			packet(entries.subList(index, index + 1)));
	}

	private static Source postedAt(final int index) {
		return (fleet, entries) -> () -> fleet.host("gn-0").post(entries.get(index));
	}

	/* The first write of a flood to a client is held while the chain takes the next entry of the feed in from the
	   second source, which has to come back without waiting for it. Answers the entries the client was written, in
	   order, once the write is let go. */
	private static List<Entry> floodedWhileAWriteIsHeld(final Source first, final Source second) {
		final ChainFleet fleet = ChainFleet.group();
		final GatedStream client = new GatedStream();
		final List<Entry> entries = entriesOf(fleet.chain(), 2);

		fleet.host("gn-0").join(client, JoinChain.builder().requestId(1).writer(ChainFleet.writer(2))
			.chainId(fleet.chain()).build());

		final Task<?> holding = new Task<>(first.of(fleet, entries)).start();

		awaitIn(client.writing);
		new Task<>(second.of(fleet, entries)).start().result();
		client.released.countDown();
		holding.result();
		return written(client);
	}

	/* The listener of the first entry stays busy while the other members bring the entries after it */
	@Example
	public void offersEntriesInChainOrderWhenTwoMembersRelayAFeedAtOnce() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = entriesOf(fleet.chain(), 3);

		final Relayed relayed = relayedAtOnce(fleet, entries, () -> { /* Nothing to do while the first is held */ });

		assertThat(relayed.heardWhileHeld(), is(empty()));
		assertThat(relayed.heard(), equalTo(entries));
	}

	@Example
	public void offersEachEntryAChainTookInOnceAndInOrder() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Offered> offered = listen(fleet.host("gn-0"));
		final EmbeddedChannel client = fleet.client("gn-0", 1);
		final List<Entry> entries = entriesOf(fleet.chain(), 5);

		post(fleet, "gn-0", client, entries);
		post(fleet, "gn-0", client, entries);
		post(fleet, "gn-0", fleet.stream("gn-0", "gn-1"), entries);

		assertThat(heard(offered), equalTo(entries));
		offered.forEach(each -> assertThat(each.chainId(), equalTo(fleet.chain())));
	}

	@Example
	public void offersAnEntryOnceWhenThreeMembersRelayItWithOverlappingPackets() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Offered> offered = listen(fleet.host("gn-0"));
		final List<Entry> entries = entriesOf(fleet.chain(), 5);

		post(fleet, "gn-0", fleet.stream("gn-0", "gn-1"), entries.subList(0, 3));
		post(fleet, "gn-0", fleet.stream("gn-0", "gn-2"), entries.subList(1, 4));
		post(fleet, "gn-0", fleet.stream("gn-0", "gn-3"), entries.subList(0, 5));
		post(fleet, "gn-0", fleet.stream("gn-0", "gn-1"), entries.subList(3, 5));

		assertThat(heard(offered), equalTo(entries));
	}

	@Example
	public void offersTheEntriesAChainReleasesFromItsBufferInOrder() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Offered> offered = listen(fleet.host("gn-0"));
		final EmbeddedChannel client = fleet.client("gn-0", 1);
		final List<Entry> entries = entriesOf(fleet.chain(), 5);

		post(fleet, "gn-0", client, entries.subList(1, 5));
		assertThat(offered, is(empty()));

		post(fleet, "gn-0", client, entries.subList(0, 1));
		assertThat(heard(offered), equalTo(entries));
	}

	@Example
	public void offersAnEntryHeldBackOnceWhenItIsSentAgainBeforeItsGapFills() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Offered> offered = listen(fleet.host("gn-0"));
		final EmbeddedChannel client = fleet.client("gn-0", 1);
		final List<Entry> entries = entriesOf(fleet.chain(), 5);

		post(fleet, "gn-0", client, entries.subList(2, 5));
		post(fleet, "gn-0", client, entries.subList(2, 5));
		post(fleet, "gn-0", fleet.stream("gn-0", "gn-1"), entries.subList(1, 5));
		assertThat(offered, is(empty()));

		post(fleet, "gn-0", client, entries.subList(0, 1));
		assertThat(heard(offered), equalTo(entries));
	}

	@Example
	public void offersAnOpenOnceHoweverManyMembersRelayIt() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Offered> offered = listen(fleet.host("gn-2"));
		final Entry open = new FeedWriter(ChainFleet.key(1), fleet.chain()).write(ChainFleet.NOW,
			new Fingerprint(new byte[Fingerprint.SIZE]), EntryKind.OPEN, new byte[] { 1 });

		post(fleet, "gn-0", fleet.client("gn-0", 1), List.of(open));

		assertThat(fleet.deliveries.stream().filter(sent -> sent.to().equals("gn-2")
			&& sent.packet() instanceof ChainEntry relayed && relayed.getEntries().contains(open)).count(), is(3L));
		assertThat(heard(offered), contains(open));
	}

	@Example
	public void offersEntriesOfEveryKindAndLeavesTheOpensMarkedByKind() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Offered> offered = listen(fleet.host("gn-0"));
		final FeedWriter feed = new FeedWriter(ChainFleet.key(1), fleet.chain());
		final List<EntryKind> kinds = List.of(EntryKind.SEALED, EntryKind.OPEN, EntryKind.ACCEPT, EntryKind.JOIN,
			EntryKind.LEAVE, EntryKind.OPEN);
		final List<Entry> entries = kinds.stream()
			.map(kind -> feed.write(ChainFleet.NOW, Fingerprint.NONE, kind, new byte[] { 1 })).toList();

		post(fleet, "gn-0", fleet.client("gn-0", 1), entries);

		assertThat(heard(offered), equalTo(entries));
		assertThat(offered.stream().map(each -> each.entry().header().kind()).toList(), equalTo(kinds));
	}

	@Example
	public void offersNothingThatWasRefusedOrDropped() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final List<Offered> offered = listen(fleet.host("gn-0"));
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 6, ChainFleet.NOW);

		post(fleet, "gn-0", new EmbeddedChannel(), entries);
		assertThat(offered, is(empty()));

		post(fleet, "gn-0", fleet.client("gn-0", 1), entries);
		assertThat(heard(offered), equalTo(entries.subList(0, 5)));
	}

	@Example
	public void offersNothingThatTheChainRejects() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Offered> offered = listen(fleet.host("gn-0"));
		final ChainId foreign = new ChainId(new byte[ChainId.SIZE]);
		final List<Entry> rejected = List.of(
			ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1, ChainFleet.NOW - 60_000).get(0),
			ChainFleet.entries(ChainFleet.key(2), fleet.chain(), 1, ChainFleet.NOW + 60_000).get(0),
			ChainFleet.entries(ChainFleet.key(3), foreign, 1, ChainFleet.NOW).get(0));

		post(fleet, "gn-0", fleet.stream("gn-0", "gn-1"), rejected);

		assertThat(offered, is(empty()));
	}

	@Example
	public void offersNothingWhenNoChainIsHosted() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<Offered> offered = listen(host);
		final List<Entry> entries = entriesOf(fleet.chain(), 2);

		fleet.settings.set(Optional.empty());
		fleet.refreshAll();

		assertThat(host.hostedChains(), is(empty()));
		assertThat(host.receive(fleet.stream("gn-0", "gn-1"), packet(entries)), is(empty()));
		assertThat(offered, is(empty()));
	}

	@Example
	public void offersNothingForAPacketWithoutEntries() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<Offered> offered = listen(host);

		assertThat(host.receive(fleet.stream("gn-0", "gn-1"), new ChainEntry()), is(empty()));
		assertThat(host.receive(fleet.stream("gn-0", "gn-1"), packet(List.of())), is(empty()));
		assertThat(offered, is(empty()));
	}

	@Example
	public void offersTheEntriesOfARetiredChainToo() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final ChainId retired = fleet.chain();
		final List<Offered> offered = listen(host);

		fleet.clock.addAndGet(EPOCH_MILLIS);
		fleet.refreshAll();

		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), retired, 2, fleet.clock.get());

		assertThat(host.retiredChains(), hasItem(retired));
		assertThat(host.hostedChains(), not(hasItem(retired)));

		post(fleet, "gn-0", fleet.stream("gn-0", "gn-1"), entries);

		assertThat(heard(offered), equalTo(entries));
		offered.forEach(each -> assertThat(each.chainId(), equalTo(retired)));
	}

	@Example
	public void offersTheEntriesOfSeveralChainsOfOnePacketInOrderOfEachChain() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<ChainId> chains = host.hostedChains();
		final List<Entry> first = entriesOf(chains.get(0), 3);
		final List<Entry> second = entriesOf(chains.get(1), 3);
		final List<Offered> offered = listen(host);

		post(fleet, "gn-0", fleet.stream("gn-0", "gn-1"), List.of(first.get(0), second.get(0), first.get(1),
			second.get(1), first.get(2), second.get(2)));

		assertThat(heardOn(offered, chains.get(0)), equalTo(first));
		assertThat(heardOn(offered, chains.get(1)), equalTo(second));
		assertThat(offered, hasSize(6));
	}

	@Example
	public void doesNotOfferTheEntriesThisNodeWritesItself() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Offered> offered = listen(fleet.host("gn-0"));

		entriesOf(fleet.chain(), 2).forEach(fleet.host("gn-0")::post);

		assertThat(offered, is(empty()));
	}

	@Example
	public void tellsTheOtherListenersWhenOneFails() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<Offered> offered = listen(host);

		host.addEntryListener((chainId, entry) -> {
			throw new IllegalStateException("A listener that fails");
		});
		final List<Offered> after = listen(host);

		post(fleet, "gn-0", fleet.client("gn-0", 1), ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 2,
			ChainFleet.NOW));

		assertThat(offered.size(), is(2));
		assertThat(after.size(), is(2));
	}

	@Example
	public void goesOnWithTheNextPacketAndLogsOnlyTheClassWhenAListenerFails() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<Entry> entries = entriesOf(fleet.chain(), 4);

		host.addEntryListener((chainId, entry) -> {
			throw new IllegalStateException(DETAIL);
		});
		final List<Offered> after = listen(host);

		final List<ILoggingEvent> warnings = ChainFleet.warningsWhile(() -> {
			post(fleet, "gn-0", fleet.stream("gn-0", "gn-1"), entries.subList(0, 2));
			relay(fleet, "gn-2", entries.subList(2, 4)).start().result();
		});

		assertThat(heard(after), equalTo(entries));
		assertThat(warnings, hasSize(4));
		warnings.forEach(warning -> {
			assertThat(warning.getFormattedMessage(), containsString(IllegalStateException.class.getName()));
			assertThat(warning.getFormattedMessage(), not(containsString(DETAIL)));
			assertThat(warning.getThrowableProxy(), is(nullValue()));
		});
	}

	@Example
	public void letsTheNextStreamThroughWhenAListenerThrowsAnError() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<Entry> entries = entriesOf(fleet.chain(), 2);
		final AtomicBoolean failing = new AtomicBoolean(true);

		host.addEntryListener((chainId, entry) -> {
			if (failing.getAndSet(false)) {
				throw new LinkageError(DETAIL);
			}
		});
		final List<Offered> after = listen(host);

		assertThrows(LinkageError.class, () -> host.receive(fleet.stream("gn-0", "gn-1"), packet(entries.subList(0, 1))));
		relay(fleet, "gn-2", entries.subList(1, 2)).start().result();

		assertThat(heard(after), equalTo(entries.subList(1, 2)));
	}

	@Example
	public void tellsAListenerAddedWhileTheEntriesAreBeingPassedOnFromTheNextEntry() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<Entry> entries = entriesOf(fleet.chain(), 3);
		final List<Offered> added = new ArrayList<>();

		host.addEntryListener((chainId, entry) -> {
			if (entry.equals(entries.get(0))) {
				host.addEntryListener((addedTo, offered) -> added.add(new Offered(addedTo, offered)));
			}
		});

		post(fleet, "gn-0", fleet.stream("gn-0", "gn-1"), entries);

		assertThat(heard(added), equalTo(entries.subList(1, 3)));
	}

	@Example
	public void letsAListenerCallBackIntoTheHostWithoutWaitingForItself() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<Entry> entries = entriesOf(fleet.chain(), 2);
		final List<Object> answers = new CopyOnWriteArrayList<>();
		final List<Offered> offered = listen(host);

		host.addEntryListener((chainId, entry) -> {
			answers.add(host.post(entries.get(1)));
			answers.add(host.chain(chainId).isPresent());
			answers.add(host.hostedChains().contains(chainId));
		});

		relay(fleet, "gn-1", entries.subList(0, 1)).start().result();

		assertThat(answers, contains(Admission.ACCEPTED, true, true));
		assertThat(host.chain(fleet.chain()).orElseThrow().entries(), equalTo(entries));
		assertThat(heard(offered), equalTo(entries.subList(0, 1)));
	}

	@Example
	public void letsAListenerPostOnTheSameChainAndOnAnotherWithoutWaiting() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<ChainId> chains = host.hostedChains();
		final List<Entry> entries = entriesOf(chains.get(0), 2);
		final Entry elsewhere = entriesOf(chains.get(1), 1).get(0);
		final EmbeddedChannel onSame = fleet.client("gn-0", 5);
		final EmbeddedChannel onOther = new EmbeddedChannel();
		final List<Admission> answers = new CopyOnWriteArrayList<>();
		final List<Offered> offered = listen(host);

		host.join(onOther, JoinChain.builder().requestId(6).writer(ChainFleet.writer(6)).chainId(chains.get(1)).build());
		host.addEntryListener((chainId, entry) -> {
			answers.add(host.post(entries.get(1)));
			answers.add(host.post(elsewhere));
		});

		relay(fleet, "gn-1", entries.subList(0, 1)).start().result();

		assertThat(answers, contains(Admission.ACCEPTED, Admission.ACCEPTED));
		assertThat(written(onSame), equalTo(entries));
		assertThat(written(onOther), equalTo(List.of(elsewhere)));
		assertThat(heard(offered), equalTo(entries.subList(0, 1)));
	}

	@Example
	public void holdsNeitherTheDeliveryLockNorTheChainWhileAListenerRuns() {
		final ChainFleet fleet = ChainFleet.group();
		final HostedChain hosted = fleet.host("gn-0").hosted(fleet.chain()).orElseThrow();
		final Entry posted = ChainFleet.entries(ChainFleet.key(2), fleet.chain(), 1, ChainFleet.NOW).get(0);
		final List<Object> meanwhile = new CopyOnWriteArrayList<>();

		relayedAtOnce(fleet, entriesOf(fleet.chain(), 3), () -> {
			meanwhile.add(new Task<>(() -> {
				synchronized (hosted.getDeliveryLock()) {
					return hosted.getChain().heads().size();
				}
			}).start().result());
			meanwhile.add(new Task<>(() -> fleet.host("gn-0").post(posted)).start().result());
		});

		assertThat(meanwhile, contains(1, Admission.ACCEPTED));
	}

	@Example
	public void letsAListenerTakeALockThatAnotherThreadHoldsWhileItPosts() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final Object monitor = new Object();
		final Entry told = entriesOf(fleet.chain(), 1).get(0);
		final Entry posted = ChainFleet.entries(ChainFleet.key(2), fleet.chain(), 1, ChainFleet.NOW).get(0);
		final EmbeddedChannel client = fleet.client("gn-0", 5);
		final CountDownLatch holding = new CountDownLatch(1);
		final CountDownLatch inListener = new CountDownLatch(1);
		final List<Entry> heard = new CopyOnWriteArrayList<>();
		final Task<?> draining = relay(fleet, "gn-1", List.of(told));
		final Task<Admission> posting = new Task<>(() -> {
			synchronized (monitor) {
				holding.countDown();
				awaitIn(inListener);
				draining.awaitStopped();
				return host.post(posted);
			}
		});

		host.addEntryListener((chainId, entry) -> {
			inListener.countDown();

			synchronized (monitor) {
				heard.add(entry);
			}
		});

		posting.start();
		awaitIn(holding);
		draining.start();

		assertThat(posting.result(), is(Admission.ACCEPTED));
		draining.result();
		assertThat(heard, contains(told));
		assertThat(written(client), equalTo(List.of(told, posted)));
	}

	@Example
	public void finishesTheFloodAndTheListenersBeforeReceiveReturns() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel client = fleet.client("gn-0", 5);
		final List<Offered> offered = listen(host);
		final List<Entry> entries = entriesOf(fleet.chain(), 2);

		host.receive(fleet.stream("gn-0", "gn-1"), packet(entries));

		assertThat(written(client), equalTo(entries));
		assertThat(heard(offered), equalTo(entries));
	}

	@Example
	public void tellsTheListenersWhatWasAppendedBeforeALaterOfferOfThePacketFailed() {
		final Failing failing = failing(new ChainFleet(ChainFleet.MEMBERS));
		final List<Entry> entries = entriesOf(failing.chainId(), 2);
		final List<Offered> offered = listen(failing.host());
		final Entry probe = failing.armForTheSecondOffer();

		assertThrows(IllegalStateException.class, () -> failing.host().receive(failing.client(), packet(entries)));

		assertThat(heard(offered), equalTo(List.of(probe, entries.get(0))));
	}

	@Example
	public void throwsTheFailureOfTheOffersWithTheFailureOfTheDrainOnIt() {
		final Failing failing = failing(new ChainFleet(ChainFleet.MEMBERS));
		final List<Entry> entries = entriesOf(failing.chainId(), 2);
		final AtomicBoolean failingToTell = new AtomicBoolean();

		failing.host().addEntryListener((chainId, entry) -> {
			if (failingToTell.get()) {
				throw new LinkageError(DETAIL);
			}
		});
		failing.armForTheSecondOffer();
		failingToTell.set(true);

		final IllegalStateException thrown = assertThrows(IllegalStateException.class,
			() -> failing.host().receive(failing.client(), packet(entries)));

		assertThat(thrown.getSuppressed().length, is(1));
		assertThat(thrown.getSuppressed()[0], instanceOf(LinkageError.class));
	}

	@Example
	public void drainsEveryChainOfAPacketWhenTheListenerOfOneThrowsAnError() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<ChainId> chains = host.hostedChains();
		final Entry first = entriesOf(chains.get(0), 1).get(0);
		final Entry second = entriesOf(chains.get(1), 1).get(0);

		host.addEntryListener((chainId, entry) -> {
			if (entry.equals(first)) {
				throw new LinkageError(DETAIL);
			}
		});
		final List<Offered> after = listen(host);

		assertThrows(LinkageError.class, () -> host.receive(fleet.stream("gn-0", "gn-1"), packet(List.of(first, second))));

		assertThat(heard(after), equalTo(List.of(second)));
	}

	@Example
	public void keepsWhatIsQueuedWhenAListenerThrowsAnErrorAndTellsItWithTheNextEntry() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<Entry> entries = entriesOf(fleet.chain(), 3);
		final CountDownLatch inListener = new CountDownLatch(1);
		final CountDownLatch queued = new CountDownLatch(1);
		final AtomicBoolean failing = new AtomicBoolean(true);

		host.addEntryListener((chainId, entry) -> {
			if (failing.getAndSet(false)) {
				inListener.countDown();
				awaitIn(queued);
				throw new LinkageError(DETAIL);
			}
		});
		final List<Offered> after = listen(host);
		final Task<?> draining = relay(fleet, "gn-1", entries.subList(0, 1)).start();

		awaitIn(inListener);
		relay(fleet, "gn-2", entries.subList(1, 2)).start().result();
		queued.countDown();

		assertThat(assertThrows(ExecutionException.class, draining::result).getCause(), instanceOf(LinkageError.class));
		assertThat(after, is(empty()));

		relay(fleet, "gn-3", entries.subList(2, 3)).start().result();

		assertThat(heard(after), equalTo(entries.subList(1, 3)));
	}

	@Example
	public void keepsAsManyPendingDeliveriesAsTheBoundAllowsAndDropsTheNext() {
		final Backlog backlog = backlog();
		final List<Entry> entries = backlog.entries();
		final List<ILoggingEvent> warnings = ChainFleet.warningsWhile(() ->
			backlog.relayed(PENDING_DELIVERIES + 1).start().result());

		backlog.letGo().countDown();
		backlog.draining().result();
		backlog.relayed(PENDING_DELIVERIES + 2).start().result();

		assertThat(warnings, hasSize(1));
		assertThat(warnings.get(0).getFormattedMessage(), is("A chain delivery queue is full"));
		assertThat(warnings.get(0).getThrowableProxy(), is(nullValue()));
		assertThat(backlog.host().chain(backlog.fleet().chain()).orElseThrow().entries(),
			hasSize(PENDING_DELIVERIES + 3));
		assertThat(heard(backlog.offered()), equalTo(Stream.concat(entries.subList(0, PENDING_DELIVERIES + 1).stream(),
			entries.subList(PENDING_DELIVERIES + 2, PENDING_DELIVERIES + 3).stream()).toList()));
	}

	@Example
	public void warnsOnceForEachStretchInWhichTheQueueStaysFull() {
		final Backlog backlog = smallBacklog(SMALL_BOUND, DROPPED + SMALL_BOUND + 2);
		final List<Entry> entries = backlog.entries();
		final int secondHeld = SMALL_BOUND + DROPPED + 1;
		final CountDownLatch inSecond = new CountDownLatch(1);
		final CountDownLatch letGoSecond = new CountDownLatch(1);

		backlog.host().addEntryListener((chainId, entry) -> {
			if (entry.equals(entries.get(secondHeld))) {
				inSecond.countDown();
				awaitIn(letGoSecond);
			}
		});

		final List<ILoggingEvent> first = ChainFleet.warningsWhile(() ->
			backlog.relayedAll(SMALL_BOUND + 1, secondHeld).start().result());

		backlog.letGo().countDown();
		backlog.draining().result();

		final Task<?> second = backlog.relayed(secondHeld).start();

		awaitIn(inSecond);
		backlog.relayedAll(secondHeld + 1, secondHeld + 1 + SMALL_BOUND).start().result();

		final List<ILoggingEvent> again = ChainFleet.warningsWhile(() ->
			backlog.relayedAll(secondHeld + 1 + SMALL_BOUND, secondHeld + 2 + SMALL_BOUND).start().result());

		letGoSecond.countDown();
		second.result();

		assertThat(first, hasSize(1));
		assertThat(again, hasSize(1));
		assertThat(heard(backlog.offered()), equalTo(Stream.concat(entries.subList(0, SMALL_BOUND + 1).stream(),
			entries.subList(secondHeld, secondHeld + 1 + SMALL_BOUND).stream()).toList()));
	}

	@Example
	public void logsTheFullQueueOnlyAfterTheDeliveryLockIsReleased() {
		ChainFleet.class.getModule().addReads(ModuleLayer.boot().findModule("ch.qos.logback.core").orElseThrow());

		final Backlog backlog = smallBacklog(1, 2);
		final Entry other = ChainFleet.entries(ChainFleet.key(2), backlog.fleet().chain(), 1, ChainFleet.NOW).get(0);
		final StallingAppender stalling = new StallingAppender(ChainHost.class);

		try {
			final Task<?> dropping = backlog.relayed(2).start();

			awaitIn(stalling.stalled);
			relay(backlog.fleet(), "gn-3", List.of(other)).start().result();
			stalling.detach();
			dropping.result();
		} finally {
			stalling.detach();
			backlog.letGo().countDown();
		}
	}

	@Example
	public void queuesWhatItAppendedBeforeAnotherStreamCanOfferTheNextEntry() {
		final ChainFleet fleet = ChainFleet.group();
		final HostedChain hosted = fleet.host("gn-0").hosted(fleet.chain()).orElseThrow();
		final Entry other = ChainFleet.entries(ChainFleet.key(2), fleet.chain(), 1, ChainFleet.NOW).get(0);
		final CountDownLatch stalled = new CountDownLatch(1);
		final CountDownLatch released = new CountDownLatch(1);
		final AtomicBoolean stalling = new AtomicBoolean(true);

		hosted.setDeliveries(new DeliveryQueue(new LinkedBlockingQueue<>() {
			@Override
			public boolean offer(final DeliveryQueue.Delivery delivery) {
				if (stalling.getAndSet(false)) {
					stalled.countDown();
					awaitIn(released);
				}

				return super.offer(delivery);
			}
		}));

		final Task<?> queuing = relay(fleet, "gn-1", entriesOf(fleet.chain(), 1)).start();

		try {
			awaitIn(stalled);

			final Task<?> waiting = relay(fleet, "gn-2", List.of(other)).start();

			waiting.awaitStopped();
			assertThat(waiting.state(), is(Thread.State.BLOCKED));
			released.countDown();
			queuing.result();
			waiting.result();
		} finally {
			released.countDown();
		}
	}

	@Example
	public void doesNotHoldBackTheEntriesOfAnotherChainWhileOneChainIsBeingPassedOn() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<ChainId> chains = host.hostedChains();
		final Entry held = entriesOf(chains.get(0), 1).get(0);
		final Entry other = entriesOf(chains.get(1), 1).get(0);
		final CountDownLatch inFirstListener = new CountDownLatch(1);
		final CountDownLatch otherChainHeard = new CountDownLatch(1);

		host.addEntryListener((chainId, entry) -> {
			if (entry.equals(held)) {
				inFirstListener.countDown();
				awaitIn(otherChainHeard);
			} else {
				otherChainHeard.countDown();
			}
		});

		final Task<?> first = relay(fleet, "gn-1", List.of(held)).start();

		awaitIn(inFirstListener);
		relay(fleet, "gn-2", List.of(other)).start().result();
		first.result();
	}

	@Example
	public void floodsBeforeTheListenersAreTold() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel client = fleet.client("gn-0", 5);
		final List<List<Entry>> floodedWhenTold = new ArrayList<>();
		final List<Entry> entries = entriesOf(fleet.chain(), 3);

		fleet.host("gn-0").addEntryListener((chainId, entry) -> floodedWhenTold.add(written(client)));

		post(fleet, "gn-0", fleet.stream("gn-0", "gn-1"), entries);

		assertThat(floodedWhenTold, hasSize(3));
		floodedWhenTold.forEach(flooded -> assertThat(flooded, equalTo(entries)));
	}

	@Example
	public void floodsAPostInChainOrderWhileAnotherStreamIsBeingPassedOn() {
		assertThat(seqs(floodedWhileAWriteIsHeld(relayedBy("gn-1", 0), postedAt(1))), equalTo(List.of(0L, 1L)));
	}

	@Example
	public void floodsTheEntriesOfTwoStreamsInChainOrder() {
		assertThat(seqs(floodedWhileAWriteIsHeld(relayedBy("gn-1", 0), relayedBy("gn-2", 1))),
			equalTo(List.of(0L, 1L)));
	}

	@Example
	public void floodsAnEntryAStreamRelaysInChainOrderWhileAPostIsBeingFlooded() {
		assertThat(seqs(floodedWhileAWriteIsHeld(postedAt(0), relayedBy("gn-2", 1))), equalTo(List.of(0L, 1L)));
	}

	@Example
	public void letsAPosterHoldingALockPostWhileAQueuedDeliveryNeedsALockAnotherThreadHolds() {
		final ChainFleet fleet = new ChainFleet(ChainFleet.MEMBERS);
		final ExecutorService executor = daemonExecutor("delivery");
		final ChainHost host = hostWith(fleet, executor);
		final ChainId chainId = host.hostedChains().get(0);
		final EmbeddedChannel client = attached(host, chainId, 1);
		final Entry posted = ChainFleet.entries(ChainFleet.key(3), chainId, 1, ChainFleet.NOW).get(0);
		final Object posterLock = new Object();
		final Object listenerLock = new Object();
		final CountDownLatch posterHolds = new CountDownLatch(1);
		final CountDownLatch otherHolds = new CountDownLatch(1);
		final CountDownLatch told = new CountDownLatch(1);
		final Task<String> other = new Task<>(() -> {
			synchronized (listenerLock) {
				otherHolds.countDown();

				synchronized (posterLock) {
					return "through";
				}
			}
		});
		final Task<Admission> poster = new Task<>(() -> {
			synchronized (posterLock) {
				posterHolds.countDown();
				awaitIn(otherHolds);
				other.awaitStopped();
				return host.post(posted);
			}
		});

		leaveSecondQueued(host, client, entriesOf(chainId, 2));
		host.addEntryListener((id, entry) -> {
			synchronized (listenerLock) {
				told.countDown();
			}
		});

		try {
			poster.start();
			awaitIn(posterHolds);
			other.start();

			assertThat(poster.result(), is(Admission.ACCEPTED));
			assertThat(other.result(), is("through"));
			awaitIn(told);
		} finally {
			executor.shutdownNow();
		}
	}

	@Example
	public void neverRunsAListenerOnTheThreadThatPosts() {
		final ChainFleet fleet = new ChainFleet(ChainFleet.MEMBERS);
		final List<Runnable> handedOver = new CopyOnWriteArrayList<>();
		final ChainHost host = hostWith(fleet, handedOver::add);
		final ChainId chainId = host.hostedChains().get(0);
		final EmbeddedChannel client = attached(host, chainId, 1);
		final Entry posted = ChainFleet.entries(ChainFleet.key(3), chainId, 1, ChainFleet.NOW).get(0);
		final List<Thread> listenedOn = new CopyOnWriteArrayList<>();

		leaveSecondQueued(host, client, entriesOf(chainId, 2));
		host.addEntryListener((id, entry) -> listenedOn.add(Thread.currentThread()));

		assertThat(host.post(posted), is(Admission.ACCEPTED));
		assertThat(listenedOn, is(empty()));
		assertThat(written(client), is(empty()));
		assertThat(handedOver, hasSize(1));

		new Task<>(() -> {
			handedOver.get(0).run();
			return null;
		}).start().result();

		assertThat(listenedOn, hasSize(1));
		assertThat(listenedOn.get(0), is(not(Thread.currentThread())));
		assertThat(written(client), equalTo(List.of(posted)));
	}

	@Example
	public void floodsAPostBeforeItReturnsWhenTheDeliveryRunsOnTheCaller() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel client = fleet.client("gn-0", 5);
		final List<Entry> entries = entriesOf(fleet.chain(), 2);

		entries.forEach(fleet.host("gn-0")::post);

		assertThat(written(client), equalTo(entries));
	}

	@Example
	public void shutsTheDeliveryDownWhenStoppedAndPostsWithoutFailingAfterwards() {
		final ChainFleet fleet = new ChainFleet(ChainFleet.MEMBERS);
		final ExecutorService executor = daemonExecutor("delivery");
		final ChainHost host = hostWith(fleet, executor);
		final ChainId chainId = host.hostedChains().get(0);
		final EmbeddedChannel client = attached(host, chainId, 1);
		final Entry posted = ChainFleet.entries(ChainFleet.key(3), chainId, 1, ChainFleet.NOW).get(0);

		host.stop();

		assertThat(executor.isShutdown(), is(true));
		assertThat(host.post(posted), is(Admission.ACCEPTED));
		assertThat(host.chain(chainId).orElseThrow().entries(), equalTo(List.of(posted)));
		assertThat(written(client), is(empty()));
	}

	@Example
	public void deliversOnADaemonThreadOfItsOwnWhenTheHostIsMadeWithItsDependenciesOnly() {
		final ChainFleet fleet = new ChainFleet(ChainFleet.MEMBERS);
		final ChainHost host = new ChainHost(fleet.settings::get,
			new TopologyGridnodeDirectory(() -> fleet.gridnodes, () -> "gn-0"), peer -> Optional.empty());

		host.refresh();

		final ChainId chainId = host.hostedChains().get(0);
		final AtomicReference<Thread> flooded = new AtomicReference<>();
		final EmbeddedChannel client = new EmbeddedChannel() {
			@Override
			public ChannelFuture write(final Object message) {
				flooded.set(Thread.currentThread());
				return super.write(message);
			}
		};

		host.join(client, JoinChain.builder().requestId(1).writer(ChainFleet.writer(1)).chainId(chainId).build());
		host.post(ChainFleet.entries(ChainFleet.key(3), chainId, 1, System.currentTimeMillis()).get(0));
		await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> flooded.get() != null);

		final Thread delivering = flooded.get();

		assertThat(delivering.getName(), is("chain-delivery"));
		assertThat(delivering.isDaemon(), is(true));

		host.stop();
		await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> !delivering.isAlive());
	}
}
