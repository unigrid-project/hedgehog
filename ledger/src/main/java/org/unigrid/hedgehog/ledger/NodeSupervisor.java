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
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.LedgerDriver.NodeFactory;
import org.unigrid.hedgehog.ledger.Scheduler.Cancellable;

/* Holds the one node of the driver and what becomes of it. The node is given every call here, and a call that throws
   closes the node and replaces it by a new one from the factory, never used again: what the failed call would have
   done is lost, peers resend, and the new node restores what the old one had saved. A second failure of the node's own
   soon after the first halts the driver, as a node that keeps failing is better stopped than rebuilt for ever. What an
   item of a peer makes the node throw is the peer's doing: it is banned, and the replacements it causes are paced
   instead of counted, so that no peer can halt the driver. Building a node replays the whole log, which is why.

   A driver votes only through a node that was built to vote and has started, and whether it does is the node's own
   answer. A halted driver holds no open node, no vote and nothing to wait for; it keeps the ledger, to serve blocks
   from, until it is closed. Used on the serial executor only, except for the questions that any thread may ask. */
@Slf4j
final class NodeSupervisor {
	private static final long MAX_BACKOFF_MILLIS = 60_000;
	private static final int MAX_REASON_LENGTH = 200;

	private enum Phase {
		NEW, RUNNING, CLOSED
	}

	private final NodeFactory factory;
	private final Scheduler scheduler;
	private final LongSupplier clock;
	private final PeerLimiter limiter;
	private final DriverOptions options;
	private final Consumer<Runnable> control;
	private final Consumer<List<Action>> perform;
	private final Runnable stopped;
	private final Recency failures = new Recency();
	private final Recency replacements = new Recency();
	private volatile Phase phase = Phase.NEW;
	private volatile String haltReason;
	private LedgerNode node;
	private Ledger ledger;
	private boolean rebuildToVote;
	private Cancellable rebuilder = () -> { };
	private long backoff;
	private long promotionBackoff;
	private long nextPromotion;

	/* `control` posts a task to the driver's control lane, `perform` carries out the actions of an event of the node's
	   own, and `stopped` is told when the driver halts, to stop what it runs */
	@SuppressWarnings("checkstyle:ParameterNumber")
	NodeSupervisor(NodeFactory factory, Scheduler scheduler, LongSupplier clock, PeerLimiter limiter,
		DriverOptions options, Consumer<Runnable> control, Consumer<List<Action>> perform, Runnable stopped) {
		this.factory = factory;
		this.scheduler = scheduler;
		this.clock = clock;
		this.limiter = limiter;
		this.options = options;
		this.control = control;
		this.perform = perform;
		this.stopped = stopped;
	}

	/* True once, for the call that starts the driver */
	boolean open() {
		if (phase != Phase.NEW) {
			return false;
		}

		phase = Phase.RUNNING;
		return true;
	}

	/* True once, for the call that ends it */
	boolean close() {
		if (phase == Phase.CLOSED) {
			return false;
		}

		phase = Phase.CLOSED;
		rebuilder.cancel();
		closeQuietly(node);
		return true;
	}

	boolean running() {
		return phase == Phase.RUNNING && haltReason == null;
	}

	/* Running, and with a node: while a node waits to be rebuilt there is nothing to give the events to */
	boolean ready() {
		return running() && node != null;
	}

	/* True once the driver has halted, and it stays true after it is closed */
	boolean halted() {
		return haltReason != null;
	}

	Optional<String> haltReason() {
		return Optional.ofNullable(haltReason);
	}

	/* The blocks are read from the ledger, which a driver that has halted still has */
	boolean servable() {
		return ledger != null && phase != Phase.CLOSED;
	}

	/* Whether the driver gives its events to a node that votes */
	boolean votes() {
		return ready() && node.signs();
	}

	LedgerNode node() {
		return node;
	}

	Ledger ledger() {
		return ledger;
	}

	/* Whether a promotion that failed has waited long enough to be tried again */
	boolean promotionDue() {
		return clock.getAsLong() >= nextPromotion;
	}

	/* The observer is replaced by a node that votes. A driver votes only through a node that was built to vote and has
	   started; if that cannot be had the observer is built again, so that the driver goes on following the chain, and
	   the promotion waits to be tried again, longer after each failure up to a minute. Only an observer that cannot be
	   built halts the driver. */
	void promote() {
		closeQuietly(node);

		if (votingNodeStarted()) {
			promotionBackoff = 0;
		} else {
			promotionBackoff = promotionBackoff == 0 ? options.tickMillis()
				: Math.min(2 * promotionBackoff, MAX_BACKOFF_MILLIS);
			nextPromotion = clock.getAsLong() + promotionBackoff;
			restart(false);
		}
	}

	private boolean votingNodeStarted() {
		final LedgerNode built;

		try {
			built = factory.create(true);
		} catch (IOException | RuntimeException e) {
			log.atWarn().setCause(e).log("A node to vote could not be built");
			return false;
		}

		try {
			return started(built);
		} catch (IOException | RuntimeException e) {
			log.atWarn().setCause(e).log("A node to vote could not be started");
			closeQuietly(built);
			return false;
		} catch (Error e) {
			closeQuietly(built);
			halt("The ledger node hit " + describe(e));
			throw e;
		}
	}

	private boolean started(LedgerNode built) throws IOException {
		if (!built.signs()) {
			throw new IllegalStateException("The node factory built a node that does not vote");
		}

		final List<Action> actions = built.start();

		node = built;
		ledger = built.ledger();
		perform.accept(actions);
		return true;
	}

	/* The timers stay: a Timeout names a step of the engine, not of a node, and the new node asks for the ones it
	   still wants, which a timer that is pending already covers. One that no longer applies does nothing when it
	   fires. */
	void restart(boolean validator) {
		final LedgerNode built;

		try {
			built = factory.create(validator);
		} catch (IOException | RuntimeException e) {
			halt("The ledger node could not be built: " + describe(e));
			return;
		}

		if (built.signs() != validator) {
			closeQuietly(built);
			halt("The node factory built a node that " + (validator ? "does not vote" : "votes")
				+ " when asked for one that " + (validator ? "does" : "does not"));
			return;
		}

		node = built;
		ledger = built.ledger();
		run(LedgerNode::start);
	}

	/* A halted driver votes no more: the node is closed and so is the lock on the sign state, and nothing is waited
	   for. What it holds is the ledger, to serve blocks from, until it is closed. The first reason is the one that is
	   kept, as what comes after it is likely to follow from it. */
	void halt(String reason) {
		log.atError().log("The ledger driver halted: {}", reason);

		if (haltReason == null) {
			haltReason = reason;
		}

		rebuilder.cancel();
		stopped.run();
		closeQuietly(node);
	}

	void run(NodeCall<List<Action>> event) {
		eventCall(event).ifPresent(perform);
	}

	/* A call for an item of a peer, or of this node's caller. What it throws is the item's doing, not the node's:
	   whoever sent it is banned, and the node is replaced only if it cannot go on. */
	<T> Optional<T> itemCall(LedgerPeer culprit, String what, NodeCall<T> call) {
		return attempt(call, cause -> itemFailed(cause, culprit, what));
	}

	/* A call for the tick, a timeout, the start or a heartbeat of this node's own */
	<T> Optional<T> eventCall(NodeCall<T> call) {
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

	/* The node failed by its own doing or the disk's, not an item's. The first failure is made good at once; one
	   soon after it halts the driver. */
	private void failed(Exception cause) {
		final long now = clock.getAsLong();
		final boolean repeated = failures.within(now, options.restartWindowMillis());
		final boolean vote = node != null && node.signs();

		failures.mark(now);
		log.atWarn().setCause(cause).log("The ledger node failed");
		closeQuietly(node);

		if (repeated) {
			halt("The ledger node failed twice within " + options.restartWindowMillis() + " ms: "
				+ describe(cause));
		} else {
			restart(vote);
		}
	}

	/* The first replacement is made at once, and the ones that follow within the window wait twice as long each time,
	   with the events that come meanwhile dropped. */
	private void itemFailed(Exception cause, LedgerPeer culprit, String what) {
		log.atWarn().setCause(cause).log("A {} made the ledger node throw", what);

		if (culprit != null) {
			limiter.ban(culprit.id());
		}

		if (!node.usable()) {
			replaceAfterBackoff();
		}
	}

	private void replaceAfterBackoff() {
		final long now = clock.getAsLong();
		final boolean vote = node != null && node.signs();

		backoff = replacements.within(now, options.restartWindowMillis()) ? backoff : 0;
		replacements.mark(now);
		closeQuietly(node);

		if (backoff == 0) {
			restart(vote);
			backoff = options.tickMillis();
		} else {
			node = null;
			rebuildToVote = vote;
			rebuilder = scheduler.schedule(() -> control.accept(this::rebuild), backoff);
			backoff = Math.min(2 * backoff, MAX_BACKOFF_MILLIS);
		}
	}

	private void rebuild() {
		if (running() && node == null) {
			restart(rebuildToVote);
		}
	}

	/* What goes into a reason is cut short, as an exception may quote what a peer sent */
	static String describe(Throwable cause) {
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
