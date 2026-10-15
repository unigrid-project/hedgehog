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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
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

/* The only caller of a ledger node. Every entry point only hands its event to the serial executor, so the node is
   given one event at a time, in order, and what it returns is acted on only after the call has returned: by then it
   has saved what it signed. The clock, the timers, the executor and the network are given, so the driver is as
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

   Everything that goes out is sent by the engine's own order or because the node took the item in as new, and never
   back to the peer it came from. Every peer is limited before any work is done for it. */
@Slf4j
public final class LedgerDriver implements Closeable {
	private static final String NOT_RUNNING = "The ledger is not running";
	private static final String REPLACED = "The ledger node failed and was replaced: send it again";
	private static final String NOT_CHECKED = "Rejected: it could not be checked";
	private static final long MAX_BACKOFF_MILLIS = 60_000;
	private static final int MAX_REASON_LENGTH = 200;

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

	public record DriverOptions(long tickMillis, long heartbeatMillis, long statusMillis, long graceMillis,
		long restartWindowMillis, Optional<Checkpoint> trusted) {
		public static final DriverOptions DEFAULT = new DriverOptions(1_000, 10_000, 10_000, 30_000, 10_000,
			Optional.empty());

		public DriverOptions {
			Objects.requireNonNull(trusted);

			if (LongStream.of(tickMillis, heartbeatMillis, statusMillis, graceMillis, restartWindowMillis)
				.anyMatch(millis -> millis <= 0)) {
				throw new IllegalArgumentException("Every interval of the driver is above 0");
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
	private final CompletableFuture<Void> closed = new CompletableFuture<>();
	private final Map<Timeout, Cancellable> timers = new HashMap<>();
	private volatile State state = State.NEW;
	private volatile String haltReason;
	private LedgerNode node;
	private boolean signing;
	private Cancellable ticker = () -> { };
	private Cancellable rebuilder = () -> { };
	private OptionalLong lastFailure = OptionalLong.empty();
	private OptionalLong lastReplaced = OptionalLong.empty();
	private long backoff;
	private long lastHeartbeat;
	private long lastStatus;

	/* A null seed is an observer: its node never signs and the driver never signs a heartbeat */
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
		this.signing = seed != null;
	}

	public void start() {
		post(this::begin);
	}

	/* The node is closed once the serial executor gets to it, so a caller that needs its files free waits for
	   closed(). An executor that has been shut down takes nothing more, and then the node is let go of here, as a
	   lock on the sign state that nothing will release is worse than a call from another thread. */
	@Override
	public void close() {
		try {
			serial.execute(this::end);
		} catch (RejectedExecutionException e) {
			end();
		}
	}

	/* Completes when the node has been closed and the files are free for another */
	public CompletableFuture<Void> closed() {
		return closed;
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

		inbound(item, () -> receive(item, node -> gossip.message(node, message)));
	}

	/* Placeholder until the full implementation lands: a status says where the chain of a peer stands. It takes its
	   token already, so that what is made of it later is never done for a peer that is over its limit. */
	public void onStatus(LedgerPeer from, Status status) {
		inbound(new Item(from, Kind.STATUS, status), () -> { });
	}

	/* Placeholder until the full implementation lands: the blocks a peer asks for are served from the ledger. A
	   request that is refused is not answered, so that a flood of them costs the answers too. */
	public CompletableFuture<BlockBatch> onBlockRequest(LedgerPeer from, BlockRequest request) {
		final CompletableFuture<BlockBatch> reply = new CompletableFuture<>();

		inbound(new Item(from, Kind.BLOCK_REQUEST, request), () -> reply.complete(new BlockBatch(List.of())),
			() -> reply.completeExceptionally(new RejectedExecutionException("The request is refused")));
		return reply;
	}

	/* Placeholder until the full implementation lands: a peer that connects is told where this chain stands, and
	   that is a status, which has its limit */
	public void onPeerConnected(LedgerPeer from) {
		inbound(new Item(from, Kind.STATUS, null), () -> { });
	}

	/* Empty when the transaction was taken in, otherwise why not, as the ledger says it. It also goes to every peer.
	   The answer is made on the serial thread, which is also where a callback of the future runs, so a callback
	   must not wait for the driver. An executor that takes no more tasks makes this throw, not answer. */
	public CompletableFuture<Optional<String>> submit(Transaction transaction) {
		return CompletableFuture.supplyAsync(() -> guarded(() -> admit(transaction), Optional.of(NOT_RUNNING)),
			serial::execute);
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

	/* Every item of a peer takes a token of its kind before anything is done for it, and `refused` is what is done
	   when it gets none, or when there is no node to give it to. The token is the first thing asked: the work of
	   an item, such as the check of a signature, is bounded by the buckets. */
	private void inbound(Item item, Runnable handler, Runnable refused) {
		post(() -> {
			if (ready() && limiter.allow(item.from().id(), item.kind())) {
				handler.run();
			} else {
				refused.run();
			}
		});
	}

	/* Everything the driver does is given to the serial executor here, and an exception that gets out of it is the
	   failure of what the driver stands on: the network, the scheduler or a peer. It says nothing of the node, and a
	   run that stops half way has lost actions, such as a Schedule that leaves the engine without a timer, or the
	   tick that would have come again. The driver stops, and says why. */
	private void post(Runnable task) {
		serial.execute(() -> guarded(() -> {
			task.run();
			return null;
		}, null));
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
			timers.put(timeout, scheduler.schedule(() -> post(() -> fire(timeout)), delayMillis));
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

	private void begin() {
		if (state == State.NEW) {
			state = State.RUNNING;
			lastHeartbeat = clock.getAsLong();
			lastStatus = lastHeartbeat;
			scheduleTick();
			restart();
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
		ticker = scheduler.schedule(() -> post(this::tick), options.tickMillis());
	}

	/* The tick comes again whether or not there is a node to give it to */
	private void tick() {
		if (running()) {
			scheduleTick();

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
			rebuilder = scheduler.schedule(() -> post(this::rebuild), backoff);
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
