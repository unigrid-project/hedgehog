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
import java.util.function.LongSupplier;
import java.util.function.Predicate;
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
   node restores what the old one had saved. A second failure soon after the first halts the driver: a node that
   keeps failing is better stopped than restarted for ever.

   Everything that goes out is sent by the engine's own order or because the node took the item in as new, and never
   back to the peer it came from. Every peer is limited before any work is done for it. */
@Slf4j
public final class LedgerDriver implements Closeable {
	private static final String NOT_RUNNING = "The ledger is not running";
	private static final String REPLACED = "The ledger node failed and was replaced: send it again";

	/* Builds a node over the files of this node, which are in use by one node at a time: the driver closes a node
	   before it asks for the next. A signing node votes; one that does not only follows. */
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

	private final NodeFactory factory;
	private final Serial serial;
	private final Scheduler scheduler;
	private final PeerLimiter limiter;
	private final LongSupplier clock;
	private final DriverOptions options;
	private final byte[] seed;
	private final Gossip gossip;
	private final Map<Timeout, Cancellable> timers = new HashMap<>();
	private volatile State state = State.NEW;
	private volatile String haltReason;
	private LedgerNode node;
	private boolean signing;
	private Cancellable ticker = () -> { };
	private OptionalLong lastFailure = OptionalLong.empty();
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
		serial.execute(this::begin);
	}

	/* The node is closed once the serial executor gets to it, so a caller that needs its files free waits for that */
	@Override
	public void close() {
		serial.execute(this::end);
	}

	public boolean halted() {
		return state == State.HALTED;
	}

	public Optional<String> haltReason() {
		return Optional.ofNullable(haltReason);
	}

	public void onTransaction(LedgerPeer from, Transaction transaction) {
		inbound(from, Kind.TRANSACTION, node -> gossip.transaction(node, transaction), transaction);
	}

	public void onHeartbeat(LedgerPeer from, Heartbeat heartbeat) {
		inbound(from, Kind.HEARTBEAT, node -> gossip.heartbeat(node, heartbeat), heartbeat);
	}

	public void onMessage(LedgerPeer from, Message message) {
		inbound(from, Kind.CONSENSUS, node -> gossip.message(node, message), message);
	}

	/* Placeholder until the full implementation lands: a status says where the chain of a peer stands */
	public void onStatus(LedgerPeer from, Status status) {
		/* Nothing is done with it yet */
	}

	/* Placeholder until the full implementation lands: the blocks a peer asks for are served from the ledger */
	public CompletableFuture<BlockBatch> onBlockRequest(LedgerPeer from, BlockRequest request) {
		return CompletableFuture.completedFuture(new BlockBatch(List.of()));
	}

	/* Placeholder until the full implementation lands: a peer that connects is told where this chain stands */
	public void onPeerConnected(LedgerPeer from) {
		/* Nothing is done with it yet */
	}

	/* Empty when the transaction was taken in, otherwise why not, as the ledger says it. It also goes to every peer. */
	public CompletableFuture<Optional<String>> submit(Transaction transaction) {
		return CompletableFuture.supplyAsync(() -> running() ? admit(transaction) : Optional.of(NOT_RUNNING),
			serial::execute);
	}

	private Optional<String> admit(Transaction transaction) {
		return receive(null, node -> gossip.transaction(node, transaction), transaction)
			.map(Receipt::rejection).orElse(Optional.of(REPLACED));
	}

	private boolean running() {
		return state == State.RUNNING;
	}

	private void inbound(LedgerPeer from, Kind kind, NodeCall<Receipt> rule, Object wire) {
		serial.execute(() -> {
			if (running() && limiter.allow(from.id(), kind)) {
				receive(from, rule, wire);
			}
		});
	}

	/* Empty when the node failed, and then nothing of the item is acted on. `from` is null for what this node makes
	   or is given by its own caller: it is sent to every peer and no one is held to account for it. */
	private Optional<Receipt> receive(LedgerPeer from, NodeCall<Receipt> rule, Object wire) {
		final Optional<Receipt> receipt = call(rule);

		receipt.ifPresent(taken -> settle(from, wire, taken));
		return receipt;
	}

	private void settle(LedgerPeer from, Object wire, Receipt receipt) {
		if (receipt.relay()) {
			gossip.toOthers(from, wire);
		}

		if (from != null && receipt.cost() > 0) {
			limiter.misbehaved(from.id(), receipt.cost());
		}

		perform(receipt.actions(), wire, from);
	}

	private void run(NodeCall<List<Action>> event) {
		call(event).ifPresent(actions -> perform(actions, null, null));
	}

	/* The one place that gives the node a call. Whatever it throws, the node is dead. */
	private <T> Optional<T> call(NodeCall<T> call) {
		try {
			return Optional.of(call.run(node));
		} catch (IOException | RuntimeException e) {
			failed(e);
			return Optional.empty();
		}
	}

	/* The engine broadcasts a decision it took from a peer as it does its own. The peer has it already, and a decision
	   carries a whole block, so it is left out of the broadcast. */
	private void perform(List<Action> actions, Object received, LedgerPeer from) {
		actions.forEach(action -> carryOut(action, received, from));

		if (actions.stream().anyMatch(Decide.class::isInstance)) {
			sendStatus();
		}
	}

	private void carryOut(Action action, Object received, LedgerPeer from) {
		switch (action) {
			case Broadcast broadcast ->
				gossip.toOthers(broadcast.message().equals(received) ? from : null, broadcast.message());
			case Schedule schedule -> arm(schedule.timeout(), schedule.delayMillis());
			case Decide decide -> disarm(timeout -> timeout.height() <= decide.block().getHeight());
		}
	}

	/* A timer that is pending is left as it is: asking again must not push it out */
	private void arm(Timeout timeout, long delayMillis) {
		if (!timers.containsKey(timeout)) {
			timers.put(timeout, scheduler.schedule(() -> serial.execute(() -> fire(timeout)), delayMillis));
		}
	}

	private void fire(Timeout timeout) {
		if (running()) {
			timers.remove(timeout);
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
		if (state != State.CLOSED) {
			state = State.CLOSED;
			stopTimers();
			closeQuietly(node);
		}
	}

	private void scheduleTick() {
		ticker = scheduler.schedule(() -> serial.execute(this::tick), options.tickMillis());
	}

	private void tick() {
		if (running()) {
			scheduleTick();
			run(LedgerNode::tick);
			signHeartbeat();
			reportStatus();
		}
	}

	/* A heartbeat is sent only if this node's own ledger takes it in, which it does not for a key that is not a
	   validator's or a time that is not later than the last one */
	private void signHeartbeat() {
		final long now = clock.getAsLong();

		if (running() && signing && due(lastHeartbeat, now, options.heartbeatMillis())) {
			final Status tip = node.ledger().status();
			final Heartbeat beat = Heartbeat.signed(seed, tip.chainId(), tip.height(), tip.tipHash(), now);

			lastHeartbeat = now;
			receive(null, node -> gossip.heartbeat(node, beat), beat);
		}
	}

	private void reportStatus() {
		if (running() && due(lastStatus, clock.getAsLong(), options.statusMillis())) {
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

	private void failed(Exception cause) {
		final long now = clock.getAsLong();
		final boolean repeated = lastFailure.isPresent()
			&& now - lastFailure.getAsLong() < options.restartWindowMillis();

		lastFailure = OptionalLong.of(now);
		log.atWarn().setCause(cause).log("The ledger node failed");
		closeQuietly(node);

		if (repeated) {
			halt("The ledger node failed twice within " + options.restartWindowMillis() + " ms: " + cause);
		} else {
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
			halt("The ledger node could not be built: " + e);
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
		disarm(timeout -> true);
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
