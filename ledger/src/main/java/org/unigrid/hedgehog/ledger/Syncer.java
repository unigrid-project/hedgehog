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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.unigrid.hedgehog.ledger.Action.Broadcast;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.Scheduler.Cancellable;

/* A node that is behind fetches the blocks of a peer that says it is ahead, one request at a time, and gives them to
   the node as decisions, in order, so a block it is given is checked exactly as one that was decided. The answer is
   the answer to the one request this sync has out, so it takes no token of the peer: a batch nobody asked for does
   not reach the driver. What the chain should be is not left to the peer: a block that contradicts the checkpoint, or
   a second certified block at a height the ledger holds, halts the driver.

   A sync is paced, as the peer limits what it answers: the first request is made at once and the ones that follow
   are a pace apart. A batch is applied one block to a task of the control lane, so that the ticks and the timers of
   the driver come between the blocks and not after all of them. A request that gets no answer is the network's
   trouble as likely as the peer's: it is made once more after a while, and only when that fails too is the peer
   doubted, for a time that doubles with each failure in a row. What a peer serves that is not right, or does not
   give after it claimed, costs it, and it is doubted for long. Used on the serial executor only. */
final class Syncer {
	static final long PACE_MILLIS = 250;
	static final long RETRY_MILLIS = 1_000;
	static final long AHEAD_BY_ONE = 1;
	static final long AHEAD_BY_TWO = 2;
	private static final int REFUSED_BLOCK_COST = 50;
	private static final int EMPTY_BATCH_COST = 5;

	/* What the sync asks of the driver: a place in the control lane, the carrying out of an action of the node's own,
	   a status to the peers, and the decision about the vote that a sync may bring */
	record Hooks(Consumer<Runnable> control, Consumer<Action> carryOut, Runnable sendStatus, Runnable promote) {
	}

	private final NodeSupervisor supervisor;
	private final SyncState sync;
	private final PeerLimiter limiter;
	private final LongSupplier clock;
	private final DriverOptions options;
	private final Scheduler scheduler;
	private final Hooks hooks;
	private Cancellable waiting = () -> { };

	Syncer(NodeSupervisor supervisor, SyncState sync, PeerLimiter limiter, LongSupplier clock, DriverOptions options,
		Scheduler scheduler, Hooks hooks) {
		this.supervisor = supervisor;
		this.sync = sync;
		this.limiter = limiter;
		this.clock = clock;
		this.options = options;
		this.scheduler = scheduler;
		this.hooks = hooks;
	}

	/* The driver stops: nothing is waited for */
	void stop() {
		waiting.cancel();
	}

	/* A sync starts for a claim that is `margin` or more above the ledger. A peer that is one block ahead is how a peer
	   that decided first looks to the rest, and it is not fetched from at once: the block is almost always decided
	   here a moment later. A claim that is one block above still, a tick later, is. */
	void startSync(long margin) {
		if (supervisor.ready() && !sync.running()) {
			final long height = supervisor.ledger().height();

			sync.nearestAbove(height + margin - 1, clock.getAsLong()).ifPresent(claim -> {
				sync.begin(height);
				ask(claim.peer());
			});
		}
	}

	private void ask(LedgerPeer peer) {
		final BlockRequest request = new BlockRequest(supervisor.ledger().height() + 1, BlockBatch.MAX_BLOCKS);

		asked(peer, request).whenComplete((batch, failure) -> arrived(peer, request, batch, failure));
	}

	private static CompletableFuture<BlockBatch> asked(LedgerPeer peer, BlockRequest request) {
		try {
			return Objects.requireNonNull(peer.request(request), "The peer gave no future");
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/* Asks again after a wait, if the sync still runs by then and has a node to give the blocks to */
	private void askAfter(long millis, LedgerPeer peer) {
		waiting = scheduler.schedule(() -> hooks.control().accept(() -> resume(peer)), millis);
	}

	private void resume(LedgerPeer peer) {
		if (sync.running() && supervisor.ready()) {
			ask(peer);
		} else if (sync.running()) {
			conclude();
		}
	}

	/* The answer comes from any thread */
	private void arrived(LedgerPeer peer, BlockRequest request, BlockBatch batch, Throwable failure) {
		hooks.control().accept(() -> {
			if (failure != null || batch == null) {
				lost(peer);
			} else if (supervisor.ready()) {
				take(peer, request, batch);
			} else {
				abandon(peer);
			}
		});
	}

	/* A request that fails or times out costs the peer nothing. The first time it is asked again after a wait, and
	   when it fails again the claim is dropped and the peer is doubted for a short time, so that another is tried.
	   The vote is no longer held back by a request that got no answer. */
	private void lost(LedgerPeer peer) {
		if (!supervisor.ready()) {
			abandon(peer);
		} else if (sync.retryOnce()) {
			sync.stopHolding();
			hooks.promote().run();
			askAfter(RETRY_MILLIS, peer);
		} else {
			sync.failed(peer.id(), clock.getAsLong(), options.statusMillis());
			abandon(peer);
		}
	}

	private void take(LedgerPeer peer, BlockRequest request, BlockBatch batch) {
		sync.delivered(peer.id());

		if (answers(request, batch)) {
			step(peer, batch.blocks(), 0, supervisor.ledger().height());
		} else {
			blame(peer);
			abandon(peer);
		}
	}

	/* An honest peer starts its answer where the request did and sends no more than it was asked for */
	private static boolean answers(BlockRequest request, BlockBatch batch) {
		final List<Block> blocks = batch.blocks();

		return blocks.isEmpty() || blocks.get(0).getHeight() == request.from();
	}

	/* One block, and then the next one in a task of its own */
	private void step(LedgerPeer peer, List<Block> blocks, int index, long before) {
		if (index == blocks.size()) {
			carryOn(peer, blocks.isEmpty(), before);
		} else if (apply(peer, blocks.get(index))) {
			hooks.control().accept(() -> step(peer, blocks, index + 1, before));
		} else {
			abandon(peer);
		}
	}

	/* A block the ledger took in while the request was out is an echo, unless it is another block. Any other goes to the
	   node as a decision, which checks it as it does a decided one, and one that does not move the ledger up by exactly
	   one is not right: the peer owes for it, and the rest of the batch is not looked at. */
	private boolean apply(LedgerPeer peer, Block block) {
		if (!supervisor.ready() || !vet(block)) {
			return false;
		}

		final long before = supervisor.ledger().height();

		if (block.getHeight() <= before) {
			return echoes(peer, block);
		}

		final Optional<List<Action>> actions = supervisor.itemCall(peer, "block batch",
			node -> node.onMessage(new Decision(block)));

		actions.ifPresent(this::carryOutSynced);
		return actions.isPresent() && movedUpByOne(before, peer);
	}

	/* A block the ledger holds is an echo. Another block at that height that no quorum has certified, for if one had
	   the driver would have halted, is something that no honest peer serves. */
	private boolean echoes(LedgerPeer peer, Block block) {
		final boolean conflicting = ChainGuard.conflictsWithHeld(supervisor.ledger(), block);

		if (conflicting) {
			blame(peer);
		}

		return !conflicting;
	}

	private boolean movedUpByOne(long before, LedgerPeer peer) {
		final boolean moved = supervisor.ledger().height() == before + 1;

		if (!moved) {
			blame(peer);
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
			&& broadcast.message() instanceof Decision)).forEach(hooks.carryOut());
	}

	/* Nothing from a peer that said it was ahead is a claim it cannot back up; a batch of echoes only is not a fault */
	private void carryOn(LedgerPeer peer, boolean empty, long before) {
		final long height = supervisor.ledger().height();

		if (empty) {
			limiter.misbehaved(peer.id(), EMPTY_BATCH_COST);
			discredit(peer);
		} else if (height == before) {
			abandon(peer);
		} else if (sync.claimsAbove(peer.id(), height, clock.getAsLong())) {
			sync.progressed();
			paceOrConclude(peer);
		} else {
			conclude();
		}
	}

	/* The sync goes on a pace later, if there is a node to give the blocks to by then */
	private void paceOrConclude(LedgerPeer peer) {
		if (supervisor.ready()) {
			askAfter(PACE_MILLIS, peer);
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

	/* The peers are told once where the ledger stands, not for every batch, as they limit the statuses they take */
	private void conclude() {
		final long began = sync.end();

		if (supervisor.ready() && supervisor.ledger().height() > began) {
			hooks.sendStatus().run();
		}

		hooks.promote().run();
		startSync(AHEAD_BY_TWO);
	}

	/* A block that contradicts the checkpoint, or a second certified block at a height the ledger holds, halts the
	   driver before it is applied. A peer that sends one that is not certified is wrong and no more. */
	boolean vet(Block block) {
		final Optional<String> reason = ChainGuard.contradiction(supervisor.ledger(), options, block);

		reason.ifPresent(supervisor::halt);
		return reason.isEmpty();
	}
}
