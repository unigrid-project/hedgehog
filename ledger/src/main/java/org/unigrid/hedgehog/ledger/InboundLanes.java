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

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.unigrid.hedgehog.ledger.PeerLimiter.Kind;

/* The two lanes into the serial executor. What the driver does itself, and what its caller asks of it, is in the
   control lane and runs ahead of the items of peers, which wait in a lane of their own, bounded in all and for each
   peer. A run of the executor drains the control lane and then so many items of peers that the control work that
   comes meanwhile waits for no more than that. Every item of a peer takes a place and then a token of its kind
   before anything is done for it. Any thread may add; only the serial executor takes. */
final class InboundLanes {
	private static final int INBOUND_PER_RUN = 64;

	private final Serial serial;
	private final PeerLimiter limiter;
	private final int maxQueued;
	private final int maxQueuedPerPeer;
	private final Predicate<Kind> accepting;
	private final Consumer<Runnable> guard;
	private final Queue<Runnable> controlLane = new ConcurrentLinkedQueue<>();
	private final Queue<Runnable> inboundLane = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean drainPosted = new AtomicBoolean();
	private final AtomicInteger pendingInbound = new AtomicInteger();
	private final ConcurrentMap<String, Integer> queuedPerPeer = new ConcurrentHashMap<>();
	private final AtomicLong droppedInbound = new AtomicLong();

	/* `accepting` says whether the driver can take an item of that kind now, and `guard` runs a task and stops the
	   driver if it throws */
	InboundLanes(Serial serial, PeerLimiter limiter, int maxQueued, int maxQueuedPerPeer, Predicate<Kind> accepting,
		Consumer<Runnable> guard) {
		this.serial = serial;
		this.limiter = limiter;
		this.maxQueued = maxQueued;
		this.maxQueuedPerPeer = maxQueuedPerPeer;
		this.accepting = accepting;
		this.guard = guard;
	}

	/* How many items of peers were dropped at the door because the queue was full, or the share of the peer in it */
	long dropped() {
		return droppedInbound.get();
	}

	/* How many items of peers wait for the serial executor now */
	int queued() {
		return pendingInbound.get();
	}

	/* How many peers have an item in the queue: none when it is empty, whatever passed through it */
	int queuedPeers() {
		return queuedPerPeer.size();
	}

	/* Every item of a peer takes a place in the queue and then a token of its kind before anything is done for it,
	   and `refused` is what is done when it gets none, or when there is no node to give it to. The token is the first
	   thing asked: the work of an item, such as the check of a signature, is bounded by the buckets. A peer that
	   cannot say who it is gets no place. This runs on the thread of the caller and holds nothing up. */
	void inbound(LedgerPeer from, Kind kind, Runnable handler, Runnable refused) {
		final String peer = idOf(from);

		if (peer == null || !enqueue(peer)) {
			droppedInbound.incrementAndGet();
			refused.run();
			return;
		}

		inboundLane.add(() -> serve(peer, kind, handler, refused));
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
		if (pendingInbound.incrementAndGet() > maxQueued) {
			pendingInbound.decrementAndGet();
			return false;
		}

		if (queuedPerPeer.merge(peer, 1, Integer::sum) > maxQueuedPerPeer) {
			release(peer);
			return false;
		}

		return true;
	}

	private void release(String peer) {
		pendingInbound.decrementAndGet();
		queuedPerPeer.computeIfPresent(peer, (id, count) -> count == 1 ? null : count - 1);
	}

	private void serve(String peer, Kind kind, Runnable handler, Runnable refused) {
		try {
			if (accepting.test(kind) && limiter.allow(peer, kind)) {
				handler.run();
			} else {
				refused.run();
			}
		} finally {
			release(peer);
		}
	}

	/* What the driver does itself, and what its caller asks of it, runs ahead of the items of peers: a tick, a timer
	   or a rebuild that waited behind a queue of them would be late by all of them. Never dropped. */
	void control(Runnable task) {
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
				guard.accept(task);
			}

			for (int served = 0; served < INBOUND_PER_RUN && !inboundLane.isEmpty(); served++) {
				guard.accept(inboundLane.poll());
			}
		} finally {
			drainPosted.set(false);

			if (!controlLane.isEmpty() || !inboundLane.isEmpty()) {
				wake();
			}
		}
	}
}
