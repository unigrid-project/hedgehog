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

package org.unigrid.hedgehog.ledger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.PeerLimiter.Policy;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.newFileSystem;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.state;

/* Real drivers on a world of virtual time. One event queue drives everything: the timers of the drivers, the delivery
   of what they send, the answers to their block requests and the actions of a test. A message takes a time that
   depends on its link, may be lost, and is lost when the two ends are on different sides of a partition. A driver
   that is stopped and started again is a new process over the same files: the files are closed first, a message on
   its way to or from the old one never arrives, and the peers see the connection go and come back. Nothing here reads
   the wall clock or depends on the order of a hash, so the same seed gives the same run. */
final class MemoryNetwork implements AutoCloseable {
	private static final long START_MILLIS = 10_000;
	private static final long REQUEST_TIMEOUT_MILLIS = 3_000;
	private static final long POLL_MILLIS = 20;
	private static final int MAX_PENDING_EVENTS = 200_000;
	private static final long[] DEFAULT_LATENCY_MILLIS = {5, 50};

	/* What a driver handed to one of its peers and when, whether or not the message was lost on the way */
	record Sent(long time, int from, int to, Object wire) {
	}

	private record Event(long time, long order, Runnable task) implements Comparable<Event> {
		@Override
		public int compareTo(Event other) {
			final int byTime = Long.compare(time, other.time);

			return byTime != 0 ? byTime : Long.compare(order, other.order);
		}
	}

	/* Two stations, whichever is named first */
	private record Edge(int low, int high) {
		private Edge {
			if (low > high) {
				final int swapped = low;

				low = high;
				high = swapped;
			}
		}

		private boolean has(int index) {
			return low == index || high == index;
		}

		private int other(int index) {
			return index == low ? high : low;
		}
	}

	/* The end of a connection that belongs to `owner`: what it is sent goes to `remote`, and `remote` sees its own end,
	   `back`, as the peer that sent it */
	private final class Link implements LedgerPeer {
		private final Station owner;
		private final Station remote;
		private Link back;

		private Link(Station owner, Station remote) {
			this.owner = owner;
			this.remote = remote;
		}

		@Override
		public String id() {
			return remote.id();
		}

		@Override
		public void send(Object wire) {
			sent.add(new Sent(now, owner.index, remote.index, wire));

			if (!lost(owner.index, remote.index)) {
				after(travel(owner.index, remote.index), () -> deliver(wire));
			}
		}

		@Override
		public CompletableFuture<BlockBatch> request(BlockRequest request) {
			final CompletableFuture<BlockBatch> reply = new CompletableFuture<>();

			sent.add(new Sent(now, owner.index, remote.index, request));
			after(REQUEST_TIMEOUT_MILLIS, () -> reply.completeExceptionally(
				new TimeoutException("The request timed out")));

			if (!lost(owner.index, remote.index)) {
				after(travel(owner.index, remote.index), () -> serve(request, reply));
			}

			return reply;
		}

		/* A connection that was replaced, or whose end has stopped, is not one a message arrives over */
		private boolean connected() {
			return owner.links.get(remote.index) == this && remote.links.get(owner.index) == back;
		}

		private void deliver(Object wire) {
			if (connected()) {
				final LedgerDriver target = remote.driver;

				switch (wire) {
					case Transaction transaction -> target.onTransaction(back, transaction);
					case Heartbeat heartbeat -> target.onHeartbeat(back, heartbeat);
					case Message message -> target.onMessage(back, message);
					case Status status -> target.onStatus(back, status);
					default -> throw new IllegalArgumentException("Not a wire object: " + wire);
				}
			}
		}

		/* A request that the other side refuses is not answered, so the requester waits for its timeout */
		private void serve(BlockRequest request, CompletableFuture<BlockBatch> reply) {
			if (connected()) {
				remote.driver.onBlockRequest(back, request).thenAccept(batch -> answer(batch, reply));
			}
		}

		private void answer(BlockBatch batch, CompletableFuture<BlockBatch> reply) {
			if (!lost(remote.index, owner.index)) {
				after(travel(remote.index, owner.index), () -> {
					if (connected()) {
						reply.complete(batch);
					}
				});
			}
		}
	}

	/* One node of the world: a driver over the files in its own directory. The driver, the limiter and the node
	   factory are those of the process that runs now, and start() makes a new process over the same files. */
	final class Station {
		private final int index;
		private final byte[] seed;
		private final DriverOptions options;
		private final Path directory;
		private final Map<Integer, Link> links = new TreeMap<>();
		private final List<LedgerPeer> attached = new ArrayList<>();
		private final List<Boolean> built = new ArrayList<>();
		private int life;
		private boolean up;
		private LedgerDriver driver;
		private PeerLimiter limiter;
		private BlockLog log;
		private Ledger ledger;

		/* A null seed is a node that follows and never votes */
		private Station(int index, byte[] seed, DriverOptions options) {
			this.index = index;
			this.seed = seed;
			this.options = options;
			this.directory = fileSystem.getPath("/n" + index);
		}

		int index() {
			return index;
		}

		String id() {
			return "N" + index;
		}

		boolean up() {
			return up;
		}

		/* How many processes this station has run: 2 after a restart */
		int starts() {
			return life;
		}

		LedgerDriver driver() {
			return driver;
		}

		PeerLimiter limiter() {
			return limiter;
		}

		/* The ledger of the newest node: a stopped or halted station keeps the one it had */
		Ledger ledger() {
			return ledger;
		}

		/* Whether each node the factory built for the process that runs now was asked to vote, in order */
		List<Boolean> built() {
			return List.copyOf(built);
		}

		/* The end of the connection to another station, as this station holds it, or null when there is none */
		LedgerPeer peerOf(int other) {
			return links.get(other);
		}

		/* Puts blocks in the log before the process starts, as a node that was running before has them */
		void preload(Block... blocks) throws IOException {
			try (BlockLog empty = BlockLog.open(directory.resolve("blocks.log"))) {
				for (final Block block : blocks) {
					empty.append(block);
				}
			}
		}

		/* A peer of the driver that is no other station, such as one that floods it; it is in the list of peers and
		   gets what the driver relays */
		void attach(LedgerPeer peer) {
			attached.add(peer);
		}

		void start() {
			life++;
			up = true;
			built.clear();
			limiter = new PeerLimiter(MemoryNetwork.this::now, Policy.DEFAULT);
			driver = new LedgerDriver(this::create, new DirectSerial(), scheduler(), this::peers, limiter,
				MemoryNetwork.this::now, options, seed);
			driver.start();
			connectWanted(this);
		}

		/* The process ends and lets go of its files, as a restart over them needs */
		void stop() {
			up = false;
			driver.close();
			driver.closed().join();
			disconnect(this);
			attached.clear();

			try {
				if (log != null) {
					log.close();
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		private List<LedgerPeer> peers() {
			final List<LedgerPeer> peers = new ArrayList<>(links.values());

			peers.addAll(attached);
			return peers;
		}

		private LedgerNode create(boolean signing) throws IOException {
			built.add(signing);

			if (log != null) {
				log.close();
			}

			log = BlockLog.open(directory.resolve("blocks.log"));
			ledger = new Ledger(state(1000, authority, validators), log, MemoryNetwork.this::now);
			return new LedgerNode(ledger, signing ? seed : null, Timeouts.DEFAULT, signing
				? StrictLocks.signStateOf(directory.resolve("sign.state")) : null, MemoryNetwork.this::now);
		}

		/* A timer of a process that has ended does nothing */
		private Scheduler scheduler() {
			final int mine = life;

			return (task, delayMillis) -> {
				final AtomicBoolean cancelled = new AtomicBoolean();

				after(delayMillis, () -> {
					if (!cancelled.get() && up && life == mine) {
						task.run();
					}
				});
				return () -> cancelled.set(true);
			};
		}
	}

	private final FileSystem fileSystem = newFileSystem();
	private final List<byte[]> validators;
	private final LedgerAuthority authority;
	private final Random random;
	private final PriorityQueue<Event> queue = new PriorityQueue<>();
	private final List<Station> stations = new ArrayList<>();
	private final List<Sent> sent = new ArrayList<>();
	private final List<BlockLog> scratches = new ArrayList<>();
	private final Set<Edge> wanted = new LinkedHashSet<>();
	private final Map<Edge, long[]> latencies = new HashMap<>();
	private Set<Integer> isolated = Set.of();
	private Runnable observer = () -> { };
	private long now = START_MILLIS;
	private long counter;
	private long dropped;
	private double dropRate;

	MemoryNetwork(List<byte[]> validators, LedgerAuthority authority, long seed) {
		this.validators = validators;
		this.authority = authority;
		this.random = new Random(seed);
	}

	long now() {
		return now;
	}

	List<byte[]> validators() {
		return validators;
	}

	byte[] chainId() {
		return state(1000, authority, validators).chainId();
	}

	Station add(byte[] seed, DriverOptions options) {
		final Station station = new Station(stations.size(), seed, options);

		stations.add(station);
		return station;
	}

	Station station(int index) {
		return stations.get(index);
	}

	List<Station> stations() {
		return List.copyOf(stations);
	}

	/* A ledger of the same chain over files of its own, for the blocks that no station made */
	Ledger scratch() throws IOException {
		final BlockLog scratchLog = BlockLog.open(fileSystem.getPath("/scratch" + scratches.size() + "/blocks.log"));

		scratches.add(scratchLog);
		return new Ledger(state(1000, authority, validators), scratchLog, this::now);
	}

	/* The two are connected whenever both run */
	void connect(int one, int other) {
		wanted.add(new Edge(one, other));
		connectWanted(station(one));
	}

	void connectAll() {
		for (int one = 0; one < stations.size(); one++) {
			for (int other = one + 1; other < stations.size(); other++) {
				connect(one, other);
			}
		}
	}

	/* A connection is made for every wanted pair of which both ends run and that is not connected yet, and each end
	   is told of the other as the application tells its driver of a peer that connects */
	private void connectWanted(Station station) {
		if (station.up) {
			wanted.stream().filter(edge -> edge.has(station.index))
				.map(edge -> stations.get(edge.other(station.index)))
				.filter(other -> other.up && !station.links.containsKey(other.index))
				.forEach(other -> link(station, other));
		}
	}

	private void link(Station one, Station other) {
		final Link toOther = new Link(one, other);
		final Link toOne = new Link(other, one);

		toOther.back = toOne;
		toOne.back = toOther;
		one.links.put(other.index, toOther);
		other.links.put(one.index, toOne);
		one.driver.onPeerConnected(toOther);
		other.driver.onPeerConnected(toOne);
	}

	private void disconnect(Station station) {
		stations.forEach(other -> other.links.remove(station.index));
		station.links.clear();
	}

	/* The messages between these two stations take this long, one way or the other */
	void latency(int one, int other, long min, long max) {
		latencies.put(new Edge(one, other), new long[] {min, max});
	}

	void dropRate(double rate) {
		dropRate = rate;
	}

	/* The stations of the group reach one another and no one else */
	void partition(Set<Integer> group) {
		isolated = Set.copyOf(group);
	}

	void heal() {
		isolated = Set.of();
	}

	private boolean lost(int from, int to) {
		final boolean lost = separated(from, to) || dropRate > 0 && random.nextDouble() < dropRate;

		if (lost) {
			dropped++;
		}

		return lost;
	}

	/* How many messages and requests were lost on the way, to a loss or a partition */
	long dropped() {
		return dropped;
	}

	private boolean separated(int from, int to) {
		return !isolated.isEmpty() && isolated.contains(from) != isolated.contains(to);
	}

	private long travel(int from, int to) {
		final long[] range = latencies.getOrDefault(new Edge(from, to), DEFAULT_LATENCY_MILLIS);

		return Math.min(range[1], range[0] + (long) (random.nextDouble() * (range[1] - range[0] + 1)));
	}

	/* Drivers that send what they get to each other without end make more events than they finish, and a run of that
	   fails here instead of filling the memory */
	void after(long delayMillis, Runnable task) {
		if (queue.size() >= MAX_PENDING_EVENTS) {
			throw new IllegalStateException("A storm: " + queue.size() + " events wait at " + now);
		}

		queue.add(new Event(now + Math.max(0, delayMillis), counter++, task));
	}

	/* Asked after every event of the world, so that a rule is checked at the moment it would be broken, not at the
	   moment a test happens to look */
	void observe(Runnable check) {
		observer = check;
	}

	void run(long untilMillis) {
		while (!queue.isEmpty() && queue.peek().time() <= untilMillis) {
			final Event event = queue.poll();

			now = event.time();
			event.task().run();
			observer.run();
		}

		now = Math.max(now, untilMillis);
	}

	/* True if the condition held before the limit, which is a time and not a span */
	boolean runUntil(BooleanSupplier reached, long limitMillis) {
		while (!reached.getAsBoolean() && now < limitMillis) {
			run(Math.min(limitMillis, now + POLL_MILLIS));
		}

		return reached.getAsBoolean();
	}

	List<Sent> sent() {
		return List.copyOf(sent);
	}

	List<Sent> sent(Class<?> type) {
		return sent.stream().filter(message -> type.isInstance(message.wire())).toList();
	}

	@Override
	public void close() throws IOException {
		for (final Station station : stations) {
			if (station.up) {
				station.stop();
			}
		}

		for (final BlockLog scratchLog : scratches) {
			scratchLog.close();
		}

		fileSystem.close();
	}
}
