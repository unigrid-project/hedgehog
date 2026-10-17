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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import org.unigrid.hedgehog.ledger.Action.Broadcast;
import org.unigrid.hedgehog.ledger.Action.Decide;

/* One validator's ledger, consensus engine and sign state tied together, or an observer's when there is no seed. Every
   event the engine is given ends the same way, before the driver sees any action of it: a block it decided is
   committed and the next height started, and then what the engine has signed is saved. The driver therefore sends
   nothing that is not on disk. A height that is decided and committed has no sign state kept: the node never signs at
   a height the ledger has passed, so the signatures the deciding event made are sent and not saved.

   Whatever fails reaches the caller as it is, and nothing is returned, so nothing leaves the node. A commit that
   fails leaves an engine that has decided a block the ledger has not taken in, and a save that fails leaves an engine
   ahead of its store. After any exception from start() or an event the node refuses every further event with an
   IllegalStateException, as saving the state of such an engine could hide what it lost: the driver replaces it by a
   new node over the same files, which restores the state last saved, and the decisions of its peers bring the block
   in. What the failed event signed was neither saved nor sent, so the restored node is free to sign differently for
   it. submit() and height() keep working, as they do not run the engine. outbox() does not: the engine
   behind it holds what the failed event signed and did not save, and a driver that polls it must not send that. The
   pool of transactions that wait for a block is not kept on disk, so a restart of the process loses them and their
   senders have to send them again; a driver that replaces a node hands the pool of the old ledger to the new one.

   The Decide actions in the lists that come back are information: the node has committed the block and started the
   next height already, and committing it again fails. Only the node commits; a node that is behind is given the
   Decision messages of the blocks it lacks, in order, through onMessage(). The driver serves the blocks it has
   committed to such nodes. A node that dies before it has sent the Decision of the event that decided loses that
   Decision, and its peers are told of the block only by what the driver serves.

   One process per key and per set of files is enforced, not assumed: the sign state file holds an exclusive lock from
   its first load or save until the node is closed, and a node over files that another holds does not start, but
   fails with a FileLockedException. The lock covers the sign state file only, so the driver opens exactly one node
   per data directory, and closes it before it starts another over the same files, the block log included.

   A sign state of a height above the next one means that the block log lost its tail, and the start is refused with
   a BlockLogBehindException: the operator restores the tail of the block log from a peer and never deletes the sign
   state, as a node without it could sign again at a height it has signed at.

   Events come one at a time, as the engine takes them, and the driver calls tick() about once a second, which is the
   rate the engine's rules for a node at rest are written for. */
public final class LedgerNode implements Closeable {
	private final Ledger ledger;
	private final Consensus engine;
	private final SignStore store;
	private final LongSupplier clock;
	private final boolean signs;
	/* The state the store holds, as far as this node knows: what it loaded or last saved, null when there is none */
	private SignState onDisk;
	private boolean stopped;

	@FunctionalInterface
	private interface Work {
		List<Action> run() throws IOException;
	}

	/* A null seed is an observer: it follows and commits the decisions, never votes, and neither reads nor writes
	   the store, so it needs none. A validator without a store could not keep what it signed. */
	public LedgerNode(Ledger ledger, byte[] seed, Timeouts timeouts, SignStore store, LongSupplier clock) {
		this.ledger = ledger;
		this.engine = new Consensus(ledger, ledger.chainId(), seed, timeouts);
		this.store = seed == null ? store : Objects.requireNonNull(store, "A validator needs a sign store");
		this.clock = clock;
		this.signs = seed != null;
	}

	/* The state of the next height is restored. A state of a height at or below the tip is not used: that height is
	   decided, and nothing in it can be signed again. A state above the next height is a block log that is behind it,
	   as the state is saved only after the block before it is committed, so the node has signed at heights it cannot
	   show and starting afresh could sign again at them: that ends the start. So does a state that is damaged, or that
	   this node cannot have written, as it may hide a signature. */
	public synchronized List<Action> start() throws IOException {
		return guarded(() -> {
			final long next = ledger.height() + 1;
			final Optional<SignState> kept = keptFor(next);

			return finish(at -> kept.isPresent() ? engine.restore(kept.get(), ledger.validators(), at)
				: engine.start(next, ledger.validators(), at));
		});
	}

	private Optional<SignState> keptFor(long next) throws IOException {
		final Optional<SignState> stored = signs ? store.load() : Optional.empty();

		onDisk = stored.orElse(null);

		if (stored.isPresent()) {
			requireNotAhead(stored.get(), next);
		}

		return stored.filter(state -> state.height() == next);
	}

	private static void requireNotAhead(SignState state, long next) throws BlockLogBehindException {
		if (state.height() > next) {
			throw new BlockLogBehindException("The block log is behind the sign state: the ledger is at height "
				+ (next - 1) + ", and the sign state is of height " + state.height()
				+ ". Restore the tail of the block log from a peer; never delete the sign state");
		}
	}

	public synchronized List<Action> onMessage(Message message) throws IOException {
		return run(at -> engine.onMessage(message, at));
	}

	public synchronized List<Action> onTimeout(Timeout timeout) throws IOException {
		return run(at -> engine.onTimeout(timeout, at));
	}

	/* What the driver calls about once a second: whatever the engine needs the time for, such as a mint that has
	   become due, and the messages of a node at rest that its peers may have lost. What was signed already is said
	   first, so that the proposal this tick makes is not said twice. Only the messages of resume() are kept, not its
	   timers: the timers are armed by start, restore and the event that arms them, and a driver that replaces a timer
	   by its Timeout when it is armed again would push it out every second and never see it fire. What onWork()
	   arms is new and stays. */
	public synchronized List<Action> tick() throws IOException {
		return run(at -> {
			final List<Action> actions = new ArrayList<>(broadcastsOf(engine.resume(at)));

			actions.addAll(engine.onWork(at));
			return actions;
		});
	}

	private static List<Action> broadcastsOf(List<Action> actions) {
		return actions.stream().filter(Broadcast.class::isInstance).toList();
	}

	/* Only a question to the ledger, which has its own lock, so it takes no turn of the node. That is a call of its
	   own from onWork(), which tells the engine that there may be something to propose: an event that can make it
	   sign, so it has to be saved. The driver calls onWork() after a submit that was accepted. */
	public Optional<String> submit(Transaction transaction) {
		return ledger.submit(transaction);
	}

	/* A heartbeat is no work for the engine, so there is no onWork() to follow it */
	public Optional<String> submit(Heartbeat heartbeat) {
		return ledger.submit(heartbeat);
	}

	public synchronized List<Action> onWork() throws IOException {
		return run(engine::onWork);
	}

	/* For the tests of this package, which ask the ledger questions. The ledger is not handed out beyond it, so that
	   nothing can move it past the engine: only the node commits blocks. */
	Ledger ledger() {
		return ledger;
	}

	/* The height the node is agreeing on, one above the tip of the ledger once it has started, and 0 before */
	public synchronized long height() {
		return engine.height();
	}

	/* What the node has said at this height, for a driver that says it again. A node that failed is refused it, as its
	   engine holds what the failed event signed and did not save. */
	public synchronized List<Message> outbox() {
		requireRunning();
		return engine.outbox();
	}

	/* Whether the engine stores this very message. A decision is never held for a node that took it: the commit and
	   the start of the next height belong to the event that decides, so whether a block is known is for the ledger to
	   say, by its hashAt(). It sends nothing, so it keeps answering on a node that failed, unlike outbox(). */
	public synchronized boolean holds(Message message) {
		return engine.holds(message);
	}

	/* Whether the node still takes events: it does not after an exception from start() or an event, or once it is
	   closed. A driver that gets an exception from submit() or holds(), which do not run the engine, asks it to
	   tell a node that failed from one that was asked something it could not answer. */
	public synchronized boolean usable() {
		return !stopped;
	}

	/* Whether the node was given a seed, so whether it ever votes */
	public boolean signs() {
		return signs;
	}

	/* The validators the engine has heard say two things in one view of this height. The next height starts with
	   none: the box that holds them is wiped when it starts, which this node does within the event that decides, so
	   what was heard in that event is gone by the time it returns. A caller that wants the rest reads them after each
	   event; nothing is kept across heights. */
	public synchronized List<BallotBox.Equivocation> evidence() {
		return engine.evidence();
	}

	private List<Action> run(LongFunction<List<Action>> event) throws IOException {
		return guarded(() -> finish(event));
	}

	/* A node that failed, in whatever way, has an engine that may have moved past what the store and the ledger hold,
	   so it takes no further event: not even one that looks harmless would be safe to save. */
	private List<Action> guarded(Work work) throws IOException {
		requireRunning();

		try {
			return work.run();
		} catch (IOException | RuntimeException e) {
			stopped = true;
			throw e;
		}
	}

	private void requireRunning() {
		if (stopped) {
			throw new IllegalStateException("This node has failed or been closed and takes no further event: "
				+ "start a new one over the same files");
		}
	}

	/* Lets go of the sign state, so that a new node can be started over the files, and stops this one: it takes no
	   further event. The block log is not the node's to close; whoever opened it does. */
	@Override
	public synchronized void close() throws IOException {
		stopped = true;

		if (signs) {
			store.close();
		}
	}

	/* The clock is read once, so that one call has one time. A quorum of one can decide again as soon as the next
	   height starts, so the commit goes on for as long as the engine returns a block. */
	private List<Action> finish(LongFunction<List<Action>> event) throws IOException {
		final long at = clock.getAsLong();
		final List<Action> actions = new ArrayList<>(event.apply(at));
		Optional<Block> decided = decidedIn(actions);

		while (decided.isPresent()) {
			ledger.commit(decided.get());

			final List<Action> next = engine.start(ledger.height() + 1, ledger.validators(), at);

			actions.addAll(next);
			decided = decidedIn(next);
		}

		persist();
		return actions;
	}

	private static Optional<Block> decidedIn(List<Action> actions) {
		return actions.stream().filter(Decide.class::isInstance).map(Decide.class::cast).map(Decide::block)
			.findFirst();
	}

	/* An engine that has not started holds nothing, and saving that would wipe the state a start is yet to restore. A
	   state that is on disk already is not written again, which spares a quiet node a write and a flush every tick. It
	   is counted as on disk only once the store has returned. */
	private void persist() throws IOException {
		if (!signs || engine.height() == 0) {
			return;
		}

		final SignState snapshot = engine.snapshot();

		if (!snapshot.equals(onDisk)) {
			store.save(snapshot, ledger.validators().size());
			onDisk = snapshot;
		}
	}
}
