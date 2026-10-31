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

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.unigrid.hedgehog.ledger.Door.Admission;
import org.unigrid.hedgehog.ledger.PeerLimiter.Grade;
import org.unigrid.hedgehog.ledger.PeerLimiter.Kind;
import org.unigrid.hedgehog.ledger.PeerLimiter.Quota;

/* The two lanes into the serial executor. What the driver does itself, and what its caller asks of it, is in the
   control lane and runs ahead of the items of peers, which wait in a lane of their own, bounded in items and in bytes,
   in all and for each peer. A run of the executor drains the control lane and then so many items of peers that the
   control work that comes meanwhile waits for no more than that. An item that is junk, which is what the door could
   not take for plausible, waits in a tenth of the room and takes a token of a small bucket that all junk shares, so
   that it cannot take the places or the tokens of the rest. Every item of a peer takes a place and then a token of
   its kind before anything is done for it. Any thread may add; only the serial executor takes. */
final class InboundLanes {
	private static final int INBOUND_PER_RUN = 64;

	private record Sender(String id, Quota quota) {
	}

	private final Serial serial;
	private final PeerLimiter limiter;
	private final QueueBudget budget;
	private final Predicate<Kind> accepting;
	private final Consumer<Runnable> guard;
	private final Runnable afterRun;
	private final Queue<Runnable> controlLane = new ConcurrentLinkedQueue<>();
	private final Queue<Runnable> inboundLane = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean drainPosted = new AtomicBoolean();
	private final AtomicLong droppedInbound = new AtomicLong();

	/* `accepting` says whether the driver can take an item of that kind now, `guard` runs a task and stops the driver
	   if it throws, and `afterRun` is what the serial executor does when it has drained what there was, as the last
	   task of the run */
	InboundLanes(Serial serial, PeerLimiter limiter, QueueBudget budget, Predicate<Kind> accepting,
		Consumer<Runnable> guard, Runnable afterRun) {
		this.serial = serial;
		this.limiter = limiter;
		this.budget = budget;
		this.accepting = accepting;
		this.guard = guard;
		this.afterRun = afterRun;
	}

	/* How many items of peers were dropped at the door because the queue was full, or the share of the peer in it, or
	   the peer was penalised */
	long dropped() {
		return droppedInbound.get();
	}

	/* How many items of peers wait for the serial executor now */
	int queued() {
		return budget.items();
	}

	/* How many bytes of frames those items came in */
	long queuedBytes() {
		return budget.bytes();
	}

	/* How many peers have an item in the queue: none when it is empty, whatever passed through it */
	int queuedPeers() {
		return budget.peers();
	}

	/* Every item of a peer takes a place in the queue and then a token of its kind before anything is done for it,
	   and `refused` is what is done when it gets none, or when there is no node to give it to. The token is the first
	   thing asked: the work of an item, such as the check of a signature, is bounded by the buckets. An item of a
	   peer that is penalised, or of one that cannot say who it is, gets no place. The place is as large as the frame
	   the item came in. The token is one of the peer and one of its quota (see LedgerPeer.quota). `known` says, on the
	   serial executor, whether the node holds the item already: such a copy takes a token of its sender only, and none
	   that all peers share, and is refused. This runs on the thread of the caller and holds nothing up. */
	void inbound(LedgerPeer from, Kind kind, int bytes, Admission admission, BooleanSupplier known, Runnable handler,
		Runnable refused) {
		final String peer = idOf(from);
		final long size = Math.max(1, bytes);
		final boolean junk = admission == Admission.JUNK;

		if (peer == null || admission == Admission.CUT || !budget.take(peer, size, junk)) {
			droppedInbound.incrementAndGet();
			refused.run();
			return;
		}

		final Sender sender = new Sender(peer, quotaOf(from, peer));
		final Runnable task = () -> serve(sender, kind, size, junk, known, handler, refused);

		inboundLane.add(task);

		try {
			wake();
		} catch (RejectedExecutionException e) {
			if (inboundLane.remove(task)) {
				budget.giveBack(peer, size, junk);
			}

			stopPosting();
			throw e;
		}
	}

	private static String idOf(LedgerPeer peer) {
		try {
			return peer.id();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/* A peer that cannot say what it shares its limits with has them to itself */
	private static Quota quotaOf(LedgerPeer peer, String id) {
		try {
			return new Quota(Objects.requireNonNullElse(peer.quota(), id));
		} catch (RuntimeException e) {
			return new Quota(id);
		}
	}

	private void serve(Sender sender, Kind kind, long size, boolean junk, BooleanSupplier known, Runnable handler,
		Runnable refused) {
		try {
			if (accepting.test(kind) && admitted(sender, kind, junk, known)) {
				handler.run();
			} else {
				refused.run();
			}
		} finally {
			budget.giveBack(sender.id(), size, junk);
		}
	}

	private boolean admitted(Sender sender, Kind kind, boolean junk, BooleanSupplier known) {
		if (known.getAsBoolean()) {
			limiter.allowCopy(sender.id(), sender.quota(), kind);
			return false;
		}

		return limiter.allow(sender.id(), sender.quota(), kind, junk ? Grade.JUNK : Grade.PLAUSIBLE);
	}

	/* What the driver does itself, and what its caller asks of it, runs ahead of the items of peers: a tick, a timer
	   or a rebuild that waited behind a queue of them would be late by all of them. Never dropped: it runs, or the
	   executor refuses the run and this throws. A task that was refused is taken back, since its caller was told it
	   will not run, unless a run has taken it meanwhile, and then it has run. */
	void control(Runnable task) {
		controlLane.add(task);

		try {
			wake();
		} catch (RejectedExecutionException e) {
			final boolean refused = controlLane.remove(task);

			stopPosting();

			if (refused) {
				throw e;
			}
		}
	}

	/* One run of the serial executor drains both lanes, and is posted when a lane gets a task and none is posted:
	   the executor holds one task of the driver, not as many as were sent to it. A run that the executor refuses
	   leaves the post with the thread that made it, which gives it up through stopPosting. */
	private void wake() {
		if (drainPosted.compareAndSet(false, true)) {
			serial.execute(this::drain);
		}
	}

	/* An executor that refuses a run takes no more, so what waits in the control lane is run here, on the thread
	   that holds the post, as nothing else will run it and the callers of those tasks wait for their answers. The
	   post is given up only once the lane is seen empty, since a thread that added a task meanwhile found the post
	   held, and left the task for the run that was to come. */
	private void stopPosting() {
		do {
			runControlLane();
			drainPosted.set(false);
		} while (!controlLane.isEmpty() && drainPosted.compareAndSet(false, true));
	}

	/* All of the control lane first, then so many items of peers that the control work that comes meanwhile waits
	   for no more than that, and then what the driver does at the end of a run. What is left makes the run post itself
	   again. */
	private void drain() {
		try {
			runControlLane();
			serveSome();
			guard.accept(afterRun);
		} finally {
			drainPosted.set(false);

			if (!controlLane.isEmpty() || !inboundLane.isEmpty()) {
				postAgain();
			}
		}
	}

	private void runControlLane() {
		for (Runnable task = controlLane.poll(); task != null; task = controlLane.poll()) {
			guard.accept(task);
		}
	}

	private void postAgain() {
		try {
			wake();
		} catch (RejectedExecutionException e) {
			stopPosting();
		}
	}

	/* An item may be gone when it is asked for: the thread that added it took it back when the executor refused the
	   run */
	private void serveSome() {
		for (int served = 0; served < INBOUND_PER_RUN; served++) {
			final Runnable item = inboundLane.poll();

			if (item == null) {
				return;
			}

			guard.accept(item);
		}
	}
}
