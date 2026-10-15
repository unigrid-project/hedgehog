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

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.ledger.Action.Broadcast;
import org.unigrid.hedgehog.ledger.Action.Decide;
import org.unigrid.hedgehog.ledger.Action.Schedule;
import org.unigrid.hedgehog.ledger.Gossip.Receipt;
import org.unigrid.hedgehog.ledger.PeerLimiter.Kind;
import org.unigrid.hedgehog.ledger.Scheduler.Cancellable;

/* The only caller of a ledger node. Every entry point only hands its event to one of two lanes that the serial
   executor drains, so the node is given one event at a time, and what it returns is acted on only after the call
   has returned: by then it has saved what it signed. What the driver does itself and what its caller asks of it
   is in the control lane and runs ahead of the items of peers, which wait in a lane of their own, bounded in all
   and for each peer. The clock, the timers, the executor and the network are given, so the driver is as
   deterministic as they are.

   The driver keeps the engine's side of the contract: it ticks the node about once a second, calls onWork() after
   each transaction the node took in, and keeps the timers by their Timeout, so that one that is pending is never
   asked for again, which would push it out and starve it. A Decide action is information: the node has committed the
   block already, and the timers of that height are cancelled. A node that threw is closed and replaced by a new one
   from the factory, never used again, and what the failed event would have done is lost; peers resend, and the new
   node restores what the old one had saved. A second failure of the node's own soon after the first halts the
   driver: a node that keeps failing is better stopped than restarted for ever. What an item of a peer makes the
   node throw is the peer's doing: it is banned, and the replacements it causes are paced instead of counted, so
   that no peer can halt the driver.

   A node that is behind fetches the blocks of a peer that says it is ahead, one request at a time, and gives them to
   the node as decisions, in order, so a block it is given is checked exactly as one that was decided. What the
   chain should be is not left to the peer: a block that is not the trusted checkpoint, or a second certified block at
   a height the ledger holds, halts the driver, which then only serves the blocks it has. A node that holds a key
   starts as an observer and votes only once it has caught up with the peers it has heard from, so that one whose sign
   state was lost cannot sign at a height the network has left.

   What a peer says of its chain is a claim, and only certified blocks are proof. A claim decides whom to ask, and a
   node that has not voted yet waits for the claims that stand; it moves no node out of voting. A claim lapses when
   it is not repeated, and a peer that is asked for the blocks it claims and cannot give them is cleared and not
   believed for ten minutes. A node that votes follows instead when the blocks it was given show that it was more
   than one behind.

   For the application: a node with a key waits for a peer at its tip, or for the grace period, before it votes
   after every restart. The node factory is asked for an observer first and, later in the same process, for a node
   that signs: it closes the block log between the two itself, and opens the sign state only for a node that signs.

   Everything that goes out is sent by the engine's own order or because the node took the item in as new, and never
   back to the peer it came from. Every peer is limited before any work is done for it, except that the answer to a
   request of the driver's own takes no token: there is one such request out at a time. */
@Slf4j
public final class LedgerDriver implements Closeable {
	private static final String NOT_RUNNING = "The ledger is not running";
	private static final String REPLACED = "The ledger node failed and was replaced: send it again";
	private static final String NOT_CHECKED = "Rejected: it could not be checked";
	private static final long MAX_BACKOFF_MILLIS = 60_000;
	private static final int MAX_REASON_LENGTH = 200;
	private static final int INBOUND_PER_RUN = 64;
	private static final int OTHER_CHAIN_COST = 10;
	private static final int REFUSED_BLOCK_COST = 50;
	private static final int EMPTY_BATCH_COST = 5;
	private static final int LAPSE_STATUSES = 2;

	/* Builds a node over the files of this node, which are in use by one node at a time: the driver closes a node
	   before it asks for the next. Closing a node lets go of its sign state and no more, so the factory closes the
	   block log of the node before it is, and opens it again for the next. A signing node votes; one that does not
	   only follows. */
	@FunctionalInterface
	public interface NodeFactory {
		LedgerNode create(boolean signing) throws IOException;
	}

	/* The block that the synced chain must have at that height */
	public record Checkpoint(long height, byte[] hash) {
		public Checkpoint {
			if (height < 1) {
				throw new IllegalArgumentException("A checkpoint is of height 1 or more, found " + height);
			}

			hash = Bytes.requireSize(hash, Digests.HASH_SIZE, "A checkpoint hash");
		}

		@Override
		public byte[] hash() {
			return hash.clone();
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Checkpoint checkpoint && height == checkpoint.height
				&& Arrays.equals(hash, checkpoint.hash);
		}

		@Override
		public int hashCode() {
			return 31 * Long.hashCode(height) + Arrays.hashCode(hash);
		}
	}

	/* `maxQueuedInbound` is how many items of peers may wait for the serial executor at once, and
	   `maxQueuedPerPeer` how many of them one peer may have: one more is dropped at the door. Each holds an object
	   that is already decoded, so what the queue can hold is this many of the largest thing a peer can send. */
	public record DriverOptions(long tickMillis, long heartbeatMillis, long statusMillis, long graceMillis,
		long restartWindowMillis, int maxQueuedInbound, int maxQueuedPerPeer, Optional<Checkpoint> trusted) {
		public static final DriverOptions DEFAULT = new DriverOptions(1_000, 10_000, 10_000, 30_000, 10_000, 10_000,
			100, Optional.empty());

		public DriverOptions {
			Objects.requireNonNull(trusted);

			if (LongStream.of(tickMillis, heartbeatMillis, statusMillis, graceMillis, restartWindowMillis,
				maxQueuedInbound, maxQueuedPerPeer).anyMatch(value -> value <= 0)) {
				throw new IllegalArgumentException("Every interval and limit of the driver is above 0");
			}
		}
	}

	private enum State {
		NEW, RUNNING, HALTED, CLOSED
	}

	@FunctionalInterface
	private interface NodeCall<T> {
		T run(LedgerNode node) throws IOException;
	}

	/* What a peer sent, or this node's own caller when `from` is null, and the kind of limit it was taken under */
	private record Item(LedgerPeer from, Kind kind, Object wire) {
	}

	/* What the driver does itself: nothing came from a peer, and what it says goes to every peer */
	private static final Item OWN = new Item(null, null, null);

	private final NodeFactory factory;
	private final Serial serial;
	private final Scheduler scheduler;
	private final PeerLimiter limiter;
	private final LongSupplier clock;
	private final DriverOptions options;
	private final byte[] seed;
	private final Gossip gossip;
	private final SyncState sync;
	private final CompletableFuture<Void> closed = new CompletableFuture<>();
	private final Queue<Runnable> controlLane = new ConcurrentLinkedQueue<>();
	private final Queue<Runnable> inboundLane = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean drainPosted = new AtomicBoolean();
	private final AtomicInteger pendingInbound = new AtomicInteger();
	private final ConcurrentMap<String, Integer> queuedPerPeer = new ConcurrentHashMap<>();
	private final AtomicLong droppedInbound = new AtomicLong();
	private final Map<Timeout, Cancellable> timers = new HashMap<>();
	private volatile State state = State.NEW;
	private volatile String haltReason;
	private LedgerNode node;
	/* The ledger of the newest node, which is what the blocks are served from, so it outlives a node that is gone */
	private Ledger ledger;
	private boolean signing;
	private boolean graceElapsed;
	private Cancellable ticker = () -> { };
	private Cancellable rebuilder = () -> { };
	private Cancellable graceTimer = () -> { };
	private OptionalLong lastFailure = OptionalLong.empty();
	private OptionalLong lastReplaced = OptionalLong.empty();
	private OptionalLong lastDemoted = OptionalLong.empty();
	private long backoff;
	private long lastHeartbeat;
	private long lastStatus;

	/* A null seed is an observer: its node never signs and the driver never signs a heartbeat. With a seed the driver
	   starts as an observer too, and takes the key into use when it has caught up. */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public LedgerDriver(NodeFactory factory, Serial serial, Scheduler scheduler, LedgerNetwork network,
		PeerLimiter limiter, LongSupplier clock, DriverOptions options, byte[] seed) {
		this.factory = factory;
		this.serial = serial;
		this.scheduler = scheduler;
		this.limiter = limiter;
		this.clock = clock;
		this.options = options;
		this.seed = seed == null ? null : seed.clone();
		this.gossip = new Gossip(network);
		this.sync = new SyncState(LAPSE_STATUSES * options.statusMillis());
	}

	public void start() {
		control(this::begin);
	}

	/* The node is closed once the serial executor gets to it, so a caller that needs its files free waits for
	   closed(). An executor that has been shut down takes nothing more, and then the node is let go of here, as a
	   lock on the sign state that nothing will release is worse than a call from another thread. */
	@Override
	public void close() {
		try {
			control(this::end);
		} catch (RejectedExecutionException e) {
			end();
		}
	}

	/* Completes when the node has been closed and the files are free for another */
	public CompletableFuture<Void> closed() {
		return closed;
	}

	/* How many items of peers were dropped at the door because the queue was full, or the share of the peer in it,
	   for the application to log */
	public long droppedInbound() {
		return droppedInbound.get();
	}

	/* How many items of peers wait for the serial executor now */
	public int queuedInbound() {
		return pendingInbound.get();
	}

	/* How many peers have an item in the queue: none when it is empty, whatever passed through it */
	int queuedPeers() {
		return queuedPerPeer.size();
	}

	/* True once the driver has halted, and it stays true after it is closed */
	public boolean halted() {
		return haltReason != null;
	}

	public Optional<String> haltReason() {
		return Optional.ofNullable(haltReason);
	}

	public void onTransaction(LedgerPeer from, Transaction transaction) {
		final Item item = new Item(from, Kind.TRANSACTION, transaction);

		inbound(item, () -> takeIn(item, transaction));
	}

	public void onHeartbeat(LedgerPeer from, Heartbeat heartbeat) {
		final Item item = new Item(from, Kind.HEARTBEAT, heartbeat);

		inbound(item, () -> receive(item, node -> gossip.heartbeat(node, heartbeat)));
	}

	public void onMessage(LedgerPeer from, Message message) {
		final Item item = new Item(from, Kind.CONSENSUS, message);

		inbound(item, () -> deliver(item, message));
	}

	/* A decision that contradicts the chain or the checkpoint is not given to the node: it halts the driver */
	private void deliver(Item item, Message message) {
		if (!(message instanceof Decision decision) || vet(decision.block())) {
			receive(item, node -> gossip.message(node, message));
		}
	}

	/* A status says where the chain of a peer stands. It takes its token before anything is made of it. */
	public void onStatus(LedgerPeer from, Status status) {
		inbound(new Item(from, Kind.STATUS, status), () -> heard(from, status));
	}

	/* The blocks a peer asks for are served from the ledger, also by a driver that has halted. A request that is
	   refused is not answered, so that a flood of them costs the answers too. */
	public CompletableFuture<BlockBatch> onBlockRequest(LedgerPeer from, BlockRequest request) {
		final CompletableFuture<BlockBatch> reply = new CompletableFuture<>();

		inbound(new Item(from, Kind.BLOCK_REQUEST, request),
			() -> reply.complete(new BlockBatch(ledger.blocksFrom(request.from(), request.max()))),
			() -> reply.completeExceptionally(new RejectedExecutionException("The request is refused")));
		return reply;
	}

	/* A peer that connects is told where this chain stands, and that is a status, which has its limit */
	public void onPeerConnected(LedgerPeer from) {
		inbound(new Item(from, Kind.STATUS, null), () -> Gossip.send(from, ledger.status()));
	}

	/* Empty when the transaction was taken in, otherwise why not, as the ledger says it. It also goes to every peer.
	   The answer is made on the serial thread, which is also where a callback of the future runs, so a callback
	   must not wait for the driver. An executor that takes no more tasks makes this throw, not answer. */
	public CompletableFuture<Optional<String>> submit(Transaction transaction) {
		return CompletableFuture.supplyAsync(() -> guarded(() -> admit(transaction), Optional.of(NOT_RUNNING)),
			this::control);
	}

	private Optional<String> admit(Transaction transaction) {
		final LedgerNode asked = node;
		final Item item = new Item(null, Kind.TRANSACTION, transaction);
		final Optional<Receipt> receipt = ready() ? takeIn(item, transaction) : Optional.empty();

		return receipt.isPresent() && node == asked ? receipt.get().rejection() : unanswered(asked);
	}

	private Optional<String> unanswered(LedgerNode asked) {
		if (!running()) {
			return Optional.of(NOT_RUNNING);
		}

		return Optional.of(asked != null && node == asked ? NOT_CHECKED : REPLACED);
	}

	private boolean running() {
		return state == State.RUNNING;
	}

	/* Running, and with a node: while a node waits to be rebuilt there is nothing to give the events to */
	private boolean ready() {
		return running() && node != null;
	}

	private void inbound(Item item, Runnable handler) {
		inbound(item, handler, () -> { });
	}

	/* Every item of a peer takes a place in the queue and then a token of its kind before anything is done for it,
	   and `refused` is what is done when it gets none, or when there is no node to give it to. The token is the first
	   thing asked: the work of an item, such as the check of a signature, is bounded by the buckets. A peer that
	   cannot say who it is gets no place. This runs on the thread of the caller and holds nothing up. */
	private void inbound(Item item, Runnable handler, Runnable refused) {
		final String peer = idOf(item.from());

		if (peer == null || !enqueue(peer)) {
			droppedInbound.incrementAndGet();
			refused.run();
			return;
		}

		inboundLane.add(() -> serve(peer, item, handler, refused));
		wake();
	}

	private static String idOf(LedgerPeer peer) {
		try {
			return peer.id();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/* A flood must not fill the queue before a limit applies, nor one peer take the places of the others. An item
	   that finds the queue full, or its peer's share of it, is dropped and charged to no one: with the queue full
	   there is no telling whose doing it is, and the buckets judge once there is room. */
	private boolean enqueue(String peer) {
		if (pendingInbound.incrementAndGet() > options.maxQueuedInbound()) {
			pendingInbound.decrementAndGet();
			return false;
		}

		if (queuedPerPeer.merge(peer, 1, Integer::sum) > options.maxQueuedPerPeer()) {
			release(peer);
			return false;
		}

		return true;
	}

	private void release(String peer) {
		pendingInbound.decrementAndGet();
		queuedPerPeer.computeIfPresent(peer, (id, count) -> count == 1 ? null : count - 1);
	}

	private void serve(String peer, Item item, Runnable handler, Runnable refused) {
		try {
			if (accepting(item.kind()) && limiter.allow(peer, item.kind())) {
				handler.run();
			} else {
				refused.run();
			}
		} finally {
			release(peer);
		}
	}

	/* The blocks are read from the ledger, which a driver that has halted still has, so a halt does not stop the
	   serving of them; everything else needs a node that runs */
	private boolean accepting(Kind kind) {
		return kind == Kind.BLOCK_REQUEST ? ledger != null && (running() || state == State.HALTED) : ready();
	}

	/* What the driver does itself, and what its caller asks of it, runs ahead of the items of peers: a tick, a timer
	   or a rebuild that waited behind a queue of them would be late by all of them. Never dropped. */
	private void control(Runnable task) {
		controlLane.add(task);
		wake();
	}

	/* One run of the serial executor drains both lanes, and is posted when a lane gets a task and none is posted:
	   the executor holds one task of the driver, not as many as were sent to it. */
	private void wake() {
		if (drainPosted.compareAndSet(false, true)) {
			try {
				serial.execute(this::drain);
			} catch (RejectedExecutionException e) {
				drainPosted.set(false);
				throw e;
			}
		}
	}

	/* All of the control lane first, then so many items of peers that the control work that comes meanwhile waits
	   for no more than that. What is left makes the run post itself again. */
	private void drain() {
		try {
			for (Runnable task = controlLane.poll(); task != null; task = controlLane.poll()) {
				guard(task);
			}

			for (int served = 0; served < INBOUND_PER_RUN && !inboundLane.isEmpty(); served++) {
				guard(inboundLane.poll());
			}
		} finally {
			drainPosted.set(false);

			if (!controlLane.isEmpty() || !inboundLane.isEmpty()) {
				wake();
			}
		}
	}

	/* An exception that gets out of a task is the failure of what the driver stands on: the network, the scheduler
	   or a peer. It says nothing of the node, and a run that stops half way has lost actions, such as a Schedule
	   that leaves the engine without a timer, or the tick that would have come again. The driver stops, and says
	   why. */
	private void guard(Runnable task) {
		guarded(() -> {
			task.run();
			return null;
		}, null);
	}

	private <T> T guarded(Supplier<T> work, T whenItFailed) {
		try {
			return work.get();
		} catch (RuntimeException e) {
			halt("Driver infrastructure failed: " + describe(e));
			return whenItFailed;
		}
	}

	/* The transaction goes to the ledger and, if it was taken in, on to the others, and then the node is told that
	   there may be work for it. A failure of either is the failure of the item. */
	private Optional<Receipt> takeIn(Item item, Transaction transaction) {
		final Optional<Receipt> receipt = receive(item, node -> gossip.transaction(node, transaction));

		if (receipt.isPresent() && receipt.get().rejection().isEmpty()) {
			itemCall(item, LedgerNode::onWork).ifPresent(actions -> perform(actions, item));
		}

		return receipt;
	}

	/* Empty when the node failed, and then nothing of the item is acted on. `from` is null for what this node's own
	   caller gives it: it is sent to every peer and no one is held to account for it. */
	private Optional<Receipt> receive(Item item, NodeCall<Receipt> rule) {
		final Optional<Receipt> receipt = itemCall(item, rule);

		receipt.ifPresent(taken -> settle(item, taken));
		return receipt;
	}

	private void settle(Item item, Receipt receipt) {
		if (receipt.relay()) {
			gossip.toOthers(item.from(), item.wire());
		}

		if (item.from() != null && receipt.cost() > 0) {
			limiter.misbehaved(item.from().id(), receipt.cost());
		}

		perform(receipt.actions(), item);
	}

	private void run(NodeCall<List<Action>> event) {
		eventCall(event).ifPresent(actions -> perform(actions, OWN));
	}

	/* A call for an item of a peer, or of this node's caller. What it throws is the item's doing, not the node's:
	   whoever sent it is banned, and the node is replaced only if it cannot go on. */
	private <T> Optional<T> itemCall(Item item, NodeCall<T> call) {
		return attempt(call, cause -> itemFailed(cause, item));
	}

	/* A call for the tick, a timeout, the start or a heartbeat of this node's own */
	private <T> Optional<T> eventCall(NodeCall<T> call) {
		return attempt(call, this::failed);
	}

	/* The one place that gives the node a call. A disk that fails is nobody's fault but counts against the node, and
	   so does an exception of an event. An Error leaves the node in an unknown state: the driver stops. */
	private <T> Optional<T> attempt(NodeCall<T> call, Consumer<Exception> failure) {
		try {
			return Optional.of(call.run(node));
		} catch (IOException e) {
			failed(e);
		} catch (RuntimeException e) {
			failure.accept(e);
		} catch (Error e) {
			halt("The ledger node hit " + describe(e));
			throw e;
		}

		return Optional.empty();
	}

	/* The engine broadcasts a decision it took from a peer as it does its own. The peer has it already, and a decision
	   carries a whole block, so it is left out of the broadcast. */
	private void perform(List<Action> actions, Item item) {
		actions.forEach(action -> carryOut(action, item));

		if (actions.stream().anyMatch(Decide.class::isInstance)) {
			sendStatus();
		}
	}

	private void carryOut(Action action, Item item) {
		switch (action) {
			case Broadcast broadcast -> relay(broadcast.message(), item);
			case Schedule schedule -> arm(schedule.timeout(), schedule.delayMillis());
			case Decide decide -> disarm(timeout -> timeout.height() <= decide.block().getHeight());
		}
	}

	private void relay(Message message, Item item) {
		gossip.toOthers(message.equals(item.wire()) ? item.from() : null, message);
	}

	/* A timer that is pending is left as it is: asking again must not push it out */
	private void arm(Timeout timeout, long delayMillis) {
		if (!timers.containsKey(timeout)) {
			timers.put(timeout, scheduler.schedule(() -> control(() -> fire(timeout)), delayMillis));
		}
	}

	/* A timer that fires with no node to give it to is forgotten all the same, or the node that comes next would
	   find it pending and never arm it */
	private void fire(Timeout timeout) {
		timers.remove(timeout);

		if (ready()) {
			run(node -> node.onTimeout(timeout));
		}
	}

	private void disarm(Predicate<Timeout> which) {
		timers.keySet().stream().filter(which).toList().forEach(timeout -> timers.remove(timeout).cancel());
	}

	/* A status is a claim of where the chain of a peer stands, and proves nothing: it tells this node whom to ask for
	   blocks, and gates the vote of a node that has not caught up, but moves no node out of voting. A peer of another
	   chain is no part of this network. A peer that could not back its claim is not heard for a while, and one whose
	   tip is not the block this node holds at that height is on another fork, which only certified blocks can
	   settle, so it counts for nothing and costs nothing. */
	private void heard(LedgerPeer from, Status status) {
		final long now = clock.getAsLong();

		if (!Arrays.equals(status.chainId(), ledger.chainId())) {
			limiter.misbehaved(from.id(), OTHER_CHAIN_COST);
		} else if (!sync.discredited(from.id(), now) && !contradictsHeld(status)) {
			sync.heard(from, status.height(), now);
			promoteIfCaughtUp();
			startSync();
		}
	}

	private boolean contradictsHeld(Status status) {
		return ledger.hashAt(status.height()).filter(held -> !Arrays.equals(held, status.tipHash())).isPresent();
	}

	private void promoteIfCaughtUp() {
		if (seed != null && !signing && ready() && caughtUp()) {
			become(true);
		}
	}

	/* At the height of the highest claim that stands, or, when none does, once the grace period is over. A claim stands
	   until it lapses or its peer cannot back it up, so one peer cannot keep a node from voting for longer than that. */
	private boolean caughtUp() {
		final OptionalLong highest = sync.highest(clock.getAsLong());

		return highest.isPresent() ? ledger.height() >= highest.getAsLong() : graceElapsed;
	}

	private void become(boolean validator) {
		signing = validator;
		closeQuietly(node);
		restart();
	}

	private void endGrace() {
		graceElapsed = true;
		promoteIfCaughtUp();
	}

	private void startSync() {
		if (ready() && !sync.running()) {
			sync.furthestAbove(ledger.height(), clock.getAsLong()).ifPresent(claim -> {
				sync.begin(ledger.height());
				ask(claim.peer());
			});
		}
	}

	private void ask(LedgerPeer peer) {
		final BlockRequest request = new BlockRequest(ledger.height() + 1, BlockBatch.MAX_BLOCKS);

		asked(peer, request).whenComplete((batch, failure) -> arrived(peer, request, batch, failure));
	}

	private static CompletableFuture<BlockBatch> asked(LedgerPeer peer, BlockRequest request) {
		try {
			return peer.request(request);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/* The answer comes from any thread. It is the answer to the one request this sync has out, so it takes no token of
	   the peer: a batch nobody asked for does not reach the driver, and the work of one is bounded by its 16 blocks. A
	   request that fails or times out is the network's trouble and costs the peer nothing, but its claim is dropped:
	   it is asked no more until it speaks again, and another peer is tried. */
	private void arrived(LedgerPeer peer, BlockRequest request, BlockBatch batch, Throwable failure) {
		control(() -> {
			if (failure != null || batch == null) {
				discredit(peer);
			} else if (ready()) {
				take(peer, request, batch);
			} else {
				abandon(peer);
			}
		});
	}

	private void take(LedgerPeer peer, BlockRequest request, BlockBatch batch) {
		final long before = ledger.height();

		if (!answers(request, batch)) {
			blame(peer);
			abandon(peer);
		} else if (applyAll(new Item(peer, Kind.BLOCK_BATCH, batch), batch)) {
			carryOn(peer, batch, before);
		} else {
			abandon(peer);
		}
	}

	/* An honest peer starts its answer where the request did and sends no more than it was asked for */
	private static boolean answers(BlockRequest request, BlockBatch batch) {
		final List<Block> blocks = batch.blocks();

		return blocks.isEmpty() || blocks.get(0).getHeight() == request.from();
	}

	private boolean applyAll(Item item, BlockBatch batch) {
		return batch.blocks().stream().allMatch(block -> apply(item, block));
	}

	/* A block the ledger took in while the request was out is an echo, unless it is another block. Any other goes to the
	   node as a decision, which checks it as it does a decided one, and one that does not move the ledger up by exactly
	   one is not right: the peer owes for it, and the rest of the batch is not looked at. */
	private boolean apply(Item item, Block block) {
		if (!ready() || !vet(block)) {
			return false;
		}

		final long before = ledger.height();

		if (block.getHeight() <= before) {
			return true;
		}

		final Optional<List<Action>> actions = itemCall(item, node -> node.onMessage(new Decision(block)));

		actions.ifPresent(this::carryOutSynced);
		return actions.isPresent() && movedUpByOne(before, item);
	}

	private boolean movedUpByOne(long before, Item item) {
		final boolean moved = ledger.height() == before + 1;

		if (!moved) {
			blame(item.from());
		}

		return moved;
	}

	/* A peer that serves what is not right owes for it, and is not believed for a while */
	private void blame(LedgerPeer peer) {
		limiter.misbehaved(peer.id(), REFUSED_BLOCK_COST);
		sync.discredit(peer.id(), clock.getAsLong());
	}

	/* The decision of each block fetched would go to every peer, which has the block already or fetches it itself: what
	   is said of the next height is said, and the decision is not */
	private void carryOutSynced(List<Action> actions) {
		actions.stream().filter(action -> !(action instanceof Broadcast broadcast
			&& broadcast.message() instanceof Decision)).forEach(action -> carryOut(action, OWN));
	}

	/* Nothing from a peer that said it was ahead is a claim it cannot back up; a batch of echoes only is not a fault */
	private void carryOn(LedgerPeer peer, BlockBatch batch, long before) {
		final long height = ledger.height();

		if (batch.blocks().isEmpty()) {
			limiter.misbehaved(peer.id(), EMPTY_BATCH_COST);
			discredit(peer);
		} else if (height == before) {
			abandon(peer);
		} else if (sync.claimsAbove(peer.id(), height, clock.getAsLong())) {
			demoteIfProvenBehind(height - before);
			ask(peer);
		} else {
			conclude();
		}
	}

	/* The peer is asked no more, and what it says is not counted until it has been forgotten */
	private void discredit(LedgerPeer peer) {
		sync.discredit(peer.id(), clock.getAsLong());
		abandon(peer);
	}

	private void abandon(LedgerPeer peer) {
		sync.drop(peer.id());
		conclude();
	}

	/* A status is a claim, and only certified blocks are proof. A node that votes has fallen behind when it was given
	   more than one block that the others had decided already, and then it follows until it has caught up. Building a
	   node replays the whole log, so it is done once in a restart window, even if a peer serves a few blocks and claims
	   a great many. */
	private void demoteIfProvenBehind(long applied) {
		final long now = clock.getAsLong();

		if (signing && applied > 1 && !within(lastDemoted, now)) {
			lastDemoted = OptionalLong.of(now);
			become(false);
		}
	}

	/* The peers are told once where the ledger stands, not for every batch, as they limit the statuses they take */
	private void conclude() {
		final long began = sync.end();

		if (ready() && ledger.height() > began) {
			sendStatus();
		}

		promoteIfCaughtUp();
		startSync();
	}

	/* A block that contradicts the checkpoint, or a second certified block at a height the ledger holds, halts the
	   driver before it is applied. A peer that sends one that is not certified is wrong and no more. */
	private boolean vet(Block block) {
		final Optional<String> reason = contradiction(block);

		reason.ifPresent(this::halt);
		return reason.isEmpty();
	}

	private Optional<String> contradiction(Block block) {
		if (clashesWithCheckpoint(block)) {
			return Optional.of(differsFromCheckpoint(block.getHeight()));
		}

		return forks(block) ? Optional.of("Two certified blocks at height " + block.getHeight()) : Optional.empty();
	}

	/* Only the block that comes next can be certified here, and one that is not valid is left to be refused */
	private boolean clashesWithCheckpoint(Block block) {
		return options.trusted().filter(checkpoint -> checkpoint.height() == block.getHeight()
			&& !Arrays.equals(checkpoint.hash(), block.hash())).isPresent()
			&& ledger.validateCertified(block).isEmpty();
	}

	private boolean forks(Block block) {
		return block.getHeight() <= ledger.height() && ledger.hashAt(block.getHeight())
			.filter(held -> !Arrays.equals(held, block.hash())).isPresent() && certified(block);
	}

	/* By more than 2/3 of the validators there are now, each counted once, and every signature a precommit for the
	   block. It is the only set the ledger can show, so a fork among an earlier set goes unseen. */
	private boolean certified(Block block) {
		final ValidatorSet validators = ledger.validators();
		final byte[] chainId = ledger.chainId();
		final List<Endorsement> endorsements = block.getEndorsements();

		return endorsements.stream().map(Endorsement::signer).distinct().count() >= validators.quorum()
			&& endorsements.stream().allMatch(endorsement -> validators.contains(endorsement.signer())
				&& block.isSigned(endorsement, chainId));
	}

	private void begin() {
		if (state == State.NEW) {
			state = State.RUNNING;
			lastHeartbeat = clock.getAsLong();
			lastStatus = lastHeartbeat;
			scheduleTick();
			scheduleGrace();
			restart();
			checkHeldAgainstCheckpoint();
		}
	}

	/* The blocks the node starts with are held to the checkpoint as well as the ones it fetches later */
	private void checkHeldAgainstCheckpoint() {
		if (ready()) {
			options.trusted().filter(this::contradictedByHeld)
				.ifPresent(checkpoint -> halt(differsFromCheckpoint(checkpoint.height())));
		}
	}

	private boolean contradictedByHeld(Checkpoint checkpoint) {
		return ledger.hashAt(checkpoint.height()).filter(held -> !Arrays.equals(held, checkpoint.hash()))
			.isPresent();
	}

	private static String differsFromCheckpoint(long height) {
		return "Block " + height + " differs from the trusted checkpoint";
	}

	/* Only a node with a key has anything to wait for */
	private void scheduleGrace() {
		if (seed != null) {
			graceTimer = scheduler.schedule(() -> control(this::endGrace), options.graceMillis());
		}
	}

	private void end() {
		try {
			if (state != State.CLOSED) {
				state = State.CLOSED;
				stopTimers();
				closeQuietly(node);
			}
		} finally {
			closed.complete(null);
		}
	}

	private void scheduleTick() {
		ticker = scheduler.schedule(() -> control(this::tick), options.tickMillis());
	}

	/* The tick comes again whether or not there is a node to give it to. It also looks again at whether the node is at
	   the tip, which the decisions it was given may have brought about with no status to say so. */
	private void tick() {
		if (running()) {
			scheduleTick();
			promoteIfCaughtUp();

			if (ready()) {
				run(LedgerNode::tick);
				signHeartbeat();
				reportStatus();
			}
		}
	}

	/* A heartbeat is sent only if this node's own ledger takes it in, which it does not for a key that is not a
	   validator's or a time that is not later than the last one */
	private void signHeartbeat() {
		final long now = clock.getAsLong();

		if (ready() && signing && due(lastHeartbeat, now, options.heartbeatMillis())) {
			final Status tip = node.ledger().status();
			final Heartbeat beat = Heartbeat.signed(seed, tip.chainId(), tip.height(), tip.tipHash(), now);

			lastHeartbeat = now;
			eventCall(node -> gossip.heartbeat(node, beat))
				.ifPresent(receipt -> settle(new Item(null, Kind.HEARTBEAT, beat), receipt));
		}
	}

	private void reportStatus() {
		if (ready() && due(lastStatus, clock.getAsLong(), options.statusMillis())) {
			sendStatus();
		}
	}

	private void sendStatus() {
		lastStatus = clock.getAsLong();
		gossip.toAll(node.ledger().status());
	}

	/* A clock that was set back makes it due at once, so that a long step back does not silence the driver */
	private static boolean due(long last, long now, long interval) {
		return now < last || now - last >= interval;
	}

	/* The node failed by its own doing or the disk's, not an item's. The first failure is made good at once; one
	   soon after it halts the driver, as a node that keeps failing is better stopped than rebuilt for ever. */
	private void failed(Exception cause) {
		final long now = clock.getAsLong();
		final boolean repeated = within(lastFailure, now);

		lastFailure = OptionalLong.of(now);
		log.atWarn().setCause(cause).log("The ledger node failed");
		closeQuietly(node);

		if (repeated) {
			halt("The ledger node failed twice within " + options.restartWindowMillis() + " ms: "
				+ describe(cause));
		} else {
			restart();
		}
	}

	/* The node threw on an item. A peer that can do that is banned, and does not count towards a halt, or one peer
	   could stop the driver. Building a node replays the whole log, so replacements are paced: the first is made at
	   once, and the ones that follow within the window wait twice as long each time, with the events that come
	   meanwhile dropped. */
	private void itemFailed(Exception cause, Item item) {
		log.atWarn().setCause(cause).log("A {} made the ledger node throw", item.kind());

		if (item.from() != null) {
			limiter.ban(item.from().id());
		}

		if (!node.usable()) {
			replaceAfterBackoff();
		}
	}

	private void replaceAfterBackoff() {
		final long now = clock.getAsLong();

		backoff = within(lastReplaced, now) ? backoff : 0;
		lastReplaced = OptionalLong.of(now);
		closeQuietly(node);

		if (backoff == 0) {
			restart();
			backoff = options.tickMillis();
		} else {
			node = null;
			rebuilder = scheduler.schedule(() -> control(this::rebuild), backoff);
			backoff = Math.min(2 * backoff, MAX_BACKOFF_MILLIS);
		}
	}

	private boolean within(OptionalLong last, long now) {
		return last.isPresent() && now - last.getAsLong() < options.restartWindowMillis();
	}

	private void rebuild() {
		if (running() && node == null) {
			restart();
		}
	}

	/* The timers stay: a Timeout names a step of the engine, not of a node, and the new node asks for the ones it
	   still wants, which a timer that is pending already covers. One that no longer applies does nothing when it
	   fires. */
	private void restart() {
		try {
			node = factory.create(signing);
		} catch (IOException | RuntimeException e) {
			halt("The ledger node could not be built: " + describe(e));
			return;
		}

		ledger = node.ledger();
		run(LedgerNode::start);
	}

	private void halt(String reason) {
		log.atError().log("The ledger driver halted: {}", reason);
		haltReason = reason;
		state = State.HALTED;
		stopTimers();
	}

	private void stopTimers() {
		ticker.cancel();
		rebuilder.cancel();
		graceTimer.cancel();
		disarm(timeout -> true);
	}

	/* What goes into a reason is cut short, as an exception may quote what a peer sent */
	private static String describe(Throwable cause) {
		final String text = String.valueOf(cause);

		return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
	}

	private static void closeQuietly(LedgerNode node) {
		try {
			if (node != null) {
				node.close();
			}
		} catch (IOException | RuntimeException e) {
			log.atWarn().setCause(e).log("Could not close the ledger node");
		}
	}
}
