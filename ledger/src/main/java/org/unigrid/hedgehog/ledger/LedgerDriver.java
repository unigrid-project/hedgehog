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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.unigrid.hedgehog.ledger.Action.Broadcast;
import org.unigrid.hedgehog.ledger.Action.Decide;
import org.unigrid.hedgehog.ledger.Action.Schedule;
import org.unigrid.hedgehog.ledger.Gossip.Receipt;
import org.unigrid.hedgehog.ledger.PeerLimiter.Kind;
import org.unigrid.hedgehog.ledger.Scheduler.Cancellable;

/* The only caller of a ledger node. Every entry point only hands its event to one of two lanes that the serial
   executor drains (InboundLanes), so the node is given one event at a time, and what it returns is acted on only
   after the call has returned: by then it has saved what it signed. The clock, the timers, the executor and the
   network are given, so the driver is as deterministic as they are.

   The driver keeps the engine's side of the contract: it ticks the node about once a second, calls onWork() after
   each transaction the node takes in, and keeps the timers by their Timeout, so that one that is pending is never
   asked for again, which would push it out and starve it. A Decide action is information: the node has committed the
   block already, and the timers of that height are cancelled. The node itself, and what becomes of it when it
   fails, is the supervisor's (NodeSupervisor).

   A node that is behind fetches the blocks of a peer that says it is ahead (Syncer), and a block that is not the
   trusted checkpoint, or a second certified block at a height the ledger holds, halts the driver (ChainGuard), which
   then only serves the blocks it has. A node that holds a key starts as an observer and votes only once it has
   caught up with the peers it has heard from (Promotion), so that one whose sign state was lost cannot sign at a
   height the network has left. What a peer says of its chain is a claim, and only certified blocks are proof.

   The driver fails closed. Whether it votes is the answer of its node, which is built to vote or not and checked
   when it is; a driver that halts or is closed has its node closed and no request out.

   For the application: a node with a key waits for a peer at its tip, or for the grace period, before it votes
   after every restart. The node factory is asked for an observer first and, later in the same process, for a node
   that signs: it closes the block log between the two itself, and opens the sign state only for a node that signs.

   Everything that goes out is sent by the engine's own order or because the node took the item in as new, and never
   back to the peer it came from. Every peer is limited before any work is done for it, except that the answer to a
   request of the driver's own takes no token: there is one such request out at a time. */
public final class LedgerDriver implements Closeable {
	private static final String NOT_RUNNING = "The ledger is not running";
	private static final String REPLACED = "The ledger node failed and was replaced: send it again";
	private static final String NOT_CHECKED = "Rejected: it could not be checked";
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

	/* What a peer sent, or this node's own caller when `from` is null, and the kind of limit it was taken under */
	private record Item(LedgerPeer from, Kind kind, Object wire) {
	}

	/* What the driver does itself: nothing came from a peer, and what it says goes to every peer */
	private static final Item OWN = new Item(null, null, null);

	private final Scheduler scheduler;
	private final PeerLimiter limiter;
	private final LongSupplier clock;
	private final DriverOptions options;
	private final byte[] seed;
	private final Gossip gossip;
	private final SyncState sync;
	private final InboundLanes lanes;
	private final NodeSupervisor supervisor;
	private final Promotion promotion;
	private final Syncer syncer;
	private final CompletableFuture<Void> closed = new CompletableFuture<>();
	private final Map<Timeout, Cancellable> timers = new HashMap<>();
	private Cancellable ticker = () -> { };
	private Cancellable graceTimer = () -> { };
	private long lastHeartbeat;
	private long lastStatus;

	/* A null seed is an observer: its node never signs and the driver never signs a heartbeat. With a seed the driver
	   starts as an observer too, and takes the key into use when it has caught up. */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public LedgerDriver(NodeFactory factory, Serial serial, Scheduler scheduler, LedgerNetwork network,
		PeerLimiter limiter, LongSupplier clock, DriverOptions options, byte[] seed) {
		this.scheduler = scheduler;
		this.limiter = limiter;
		this.clock = clock;
		this.options = options;
		this.seed = seed == null ? null : seed.clone();
		this.gossip = new Gossip(network);
		this.sync = new SyncState(LAPSE_STATUSES * options.statusMillis(), () -> connected(network));
		this.lanes = new InboundLanes(serial, limiter, options.maxQueuedInbound(), options.maxQueuedPerPeer(),
			this::accepting, this::guard);
		this.supervisor = new NodeSupervisor(factory, scheduler, clock, limiter, options, this::control,
			actions -> perform(actions, OWN), this::stopped);
		this.promotion = new Promotion(supervisor, sync, limiter, clock, seed != null, this::startSync);
		this.syncer = new Syncer(supervisor, sync, limiter, clock, options, scheduler,
			new Syncer.Hooks(this::control, action -> carryOut(action, OWN), this::sendStatus,
				promotion::promoteIfCaughtUp));
	}

	/* The ids of the peers that are connected now; one that cannot say who it is counts for nothing */
	private static Set<String> connected(LedgerNetwork network) {
		return network.peers().stream().map(LedgerDriver::idOf).filter(Objects::nonNull).collect(Collectors.toSet());
	}

	private static String idOf(LedgerPeer peer) {
		try {
			return peer.id();
		} catch (RuntimeException e) {
			return null;
		}
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
		return lanes.dropped();
	}

	/* How many items of peers wait for the serial executor now */
	public int queuedInbound() {
		return lanes.queued();
	}

	/* How many peers have an item in the queue: none when it is empty, whatever passed through it */
	int queuedPeers() {
		return lanes.queuedPeers();
	}

	/* For the tests of this package: whether the driver gives its events to a node that votes */
	boolean signing() {
		return supervisor.votes();
	}

	/* For the tests of this package: whether a request for blocks is out */
	boolean syncing() {
		return sync.running();
	}

	/* For the tests of this package: whether the peer claims a chain above the ledger, and the claim stands */
	boolean claimedAbove(String peer) {
		return supervisor.ledger() != null
			&& sync.claimsAbove(peer, supervisor.ledger().height(), clock.getAsLong());
	}

	/* For the tests of this package: whether what the peer says is left unheard for now */
	boolean doubts(String peer) {
		return sync.discredited(peer, clock.getAsLong());
	}

	/* True once the driver has halted, and it stays true after it is closed */
	public boolean halted() {
		return supervisor.halted();
	}

	public Optional<String> haltReason() {
		return supervisor.haltReason();
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
		if (!(message instanceof Decision decision) || syncer.vet(decision.block())) {
			receive(item, node -> gossip.message(node, message));
		}
	}

	/* A status says where the chain of a peer stands. It takes its token before anything is made of it. */
	public void onStatus(LedgerPeer from, Status status) {
		inbound(new Item(from, Kind.STATUS, status), () -> promotion.heard(from, status));
	}

	/* The blocks a peer asks for are served from the ledger, also by a driver that has halted. A request that is
	   refused is not answered, so that a flood of them costs the answers too. */
	public CompletableFuture<BlockBatch> onBlockRequest(LedgerPeer from, BlockRequest request) {
		final CompletableFuture<BlockBatch> reply = new CompletableFuture<>();

		inbound(new Item(from, Kind.BLOCK_REQUEST, request),
			() -> reply.complete(new BlockBatch(supervisor.ledger().blocksFrom(request.from(), request.max()))),
			() -> reply.completeExceptionally(new RejectedExecutionException("The request is refused")));
		return reply;
	}

	/* A peer that connects is told where this chain stands, and that is a status, which has its limit */
	public void onPeerConnected(LedgerPeer from) {
		inbound(new Item(from, Kind.STATUS, null), () -> Gossip.send(from, supervisor.ledger().status()));
	}

	/* Empty when the transaction was taken in, otherwise why not, as the ledger says it. It also goes to every peer.
	   The answer is made on the serial thread, which is also where a callback of the future runs, so a callback
	   must not wait for the driver. An executor that takes no more tasks makes this throw, not answer. */
	public CompletableFuture<Optional<String>> submit(Transaction transaction) {
		return CompletableFuture.supplyAsync(() -> guarded(() -> admit(transaction), Optional.of(NOT_RUNNING)),
			this::control);
	}

	private Optional<String> admit(Transaction transaction) {
		final LedgerNode asked = supervisor.node();
		final Item item = new Item(null, Kind.TRANSACTION, transaction);
		final Optional<Receipt> receipt = supervisor.ready() ? takeIn(item, transaction) : Optional.empty();

		return receipt.isPresent() && supervisor.node() == asked ? receipt.get().rejection() : unanswered(asked);
	}

	private Optional<String> unanswered(LedgerNode asked) {
		if (!supervisor.running()) {
			return Optional.of(NOT_RUNNING);
		}

		return Optional.of(asked != null && supervisor.node() == asked ? NOT_CHECKED : REPLACED);
	}

	private void inbound(Item item, Runnable handler) {
		inbound(item, handler, () -> { });
	}

	private void inbound(Item item, Runnable handler, Runnable refused) {
		lanes.inbound(item.from(), item.kind(), handler, refused);
	}

	/* The blocks are read from the ledger, which a driver that has halted still has, so a halt does not stop the
	   serving of them; everything else needs a node that runs */
	private boolean accepting(Kind kind) {
		return kind == Kind.BLOCK_REQUEST ? supervisor.servable() : supervisor.ready();
	}

	private void control(Runnable task) {
		lanes.control(task);
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
			supervisor.halt("Driver infrastructure failed: " + NodeSupervisor.describe(e));
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

	private <T> Optional<T> itemCall(Item item, NodeCall<T> call) {
		return supervisor.itemCall(item.from(), String.valueOf(item.kind()), call);
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

		if (supervisor.ready()) {
			supervisor.run(node -> node.onTimeout(timeout));
		}
	}

	private void disarm(Predicate<Timeout> which) {
		timers.keySet().stream().filter(which).toList().forEach(timeout -> timers.remove(timeout).cancel());
	}

	private void startSync() {
		syncer.startSync();
	}

	private void begin() {
		if (supervisor.open()) {
			lastHeartbeat = clock.getAsLong();
			lastStatus = lastHeartbeat;
			scheduleTick();
			scheduleGrace();
			supervisor.restart(false);
			checkHeldAgainstCheckpoint();
		}
	}

	/* The blocks the node starts with are held to the checkpoint as well as the ones it fetches later */
	private void checkHeldAgainstCheckpoint() {
		if (supervisor.ready()) {
			ChainGuard.heldContradiction(supervisor.ledger(), options).ifPresent(supervisor::halt);
		}
	}

	/* Only a node with a key has anything to wait for */
	private void scheduleGrace() {
		if (seed != null) {
			graceTimer = scheduler.schedule(() -> control(promotion::endGrace), options.graceMillis());
		}
	}

	private void end() {
		try {
			if (supervisor.close()) {
				stopped();
			}
		} finally {
			closed.complete(null);
		}
	}

	/* What the driver runs besides the node stops with it, and no request is out */
	private void stopped() {
		ticker.cancel();
		graceTimer.cancel();
		disarm(timeout -> true);
		syncer.stop();
		sync.clear();
	}

	private void scheduleTick() {
		ticker = scheduler.schedule(() -> control(this::tick), options.tickMillis());
	}

	/* The tick comes again whether or not there is a node to give it to. It also looks again at whether the node is at
	   the tip, which the decisions it was given may have brought about with no status to say so. */
	private void tick() {
		if (supervisor.running()) {
			scheduleTick();
			promotion.promoteIfCaughtUp();

			if (supervisor.ready()) {
				supervisor.run(LedgerNode::tick);
				signHeartbeat();
				reportStatus();
			}
		}
	}

	/* A heartbeat is sent only if this node's own ledger takes it in, which it does not for a key that is not a
	   validator's or a time that is not later than the last one */
	private void signHeartbeat() {
		final long now = clock.getAsLong();

		if (supervisor.votes() && due(lastHeartbeat, now, options.heartbeatMillis())) {
			final Status tip = supervisor.ledger().status();
			final Heartbeat beat = Heartbeat.signed(seed, tip.chainId(), tip.height(), tip.tipHash(), now);

			lastHeartbeat = now;
			supervisor.eventCall(node -> gossip.heartbeat(node, beat))
				.ifPresent(receipt -> settle(new Item(null, Kind.HEARTBEAT, beat), receipt));
		}
	}

	private void reportStatus() {
		if (supervisor.ready() && due(lastStatus, clock.getAsLong(), options.statusMillis())) {
			sendStatus();
		}
	}

	private void sendStatus() {
		lastStatus = clock.getAsLong();
		gossip.toAll(supervisor.ledger().status());
	}

	/* A clock that was set back makes it due at once, so that a long step back does not silence the driver */
	private static boolean due(long last, long now, long interval) {
		return now < last || now - last >= interval;
	}
}
