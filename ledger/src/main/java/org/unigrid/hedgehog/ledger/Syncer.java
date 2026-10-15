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
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.unigrid.hedgehog.ledger.Action.Broadcast;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.PeerLimiter.Kind;

/* A node that is behind fetches the blocks of a peer that says it is ahead, one request at a time, and gives them to
   the node as decisions, in order, so a block it is given is checked exactly as one that was decided. The answer is
   the answer to the one request this sync has out, so it takes no token of the peer: a batch nobody asked for does
   not reach the driver, and the work of one is bounded by its blocks. What the chain should be is not left to the
   peer: a block that contradicts the checkpoint, or a second certified block at a height the ledger holds, halts the
   driver. A peer that cannot give the blocks it claims is cleared and not believed for a while, and a block the
   ledger refuses costs the peer for it. Used on the serial executor only. */
final class Syncer {
	private static final int REFUSED_BLOCK_COST = 50;
	private static final int EMPTY_BATCH_COST = 5;

	/* What the sync asks of the driver: a place in the control lane, the carrying out of an action of the node's own,
	   a status to the peers, and the two decisions about the vote that a sync may bring */
	record Hooks(Consumer<Runnable> control, Consumer<Action> carryOut, Runnable sendStatus, Runnable promote,
		LongConsumer demote) {
	}

	private final NodeSupervisor supervisor;
	private final SyncState sync;
	private final PeerLimiter limiter;
	private final LongSupplier clock;
	private final DriverOptions options;
	private final Hooks hooks;

	Syncer(NodeSupervisor supervisor, SyncState sync, PeerLimiter limiter, LongSupplier clock, DriverOptions options,
		Hooks hooks) {
		this.supervisor = supervisor;
		this.sync = sync;
		this.limiter = limiter;
		this.clock = clock;
		this.options = options;
		this.hooks = hooks;
	}

	void startSync() {
		if (supervisor.ready() && !sync.running()) {
			sync.furthestAbove(supervisor.ledger().height(), clock.getAsLong()).ifPresent(claim -> {
				sync.begin(supervisor.ledger().height());
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

	/* The answer comes from any thread. A request that fails or times out is the network's trouble and costs the peer
	   nothing, but its claim is dropped: it is asked no more until it speaks again, and another peer is tried. */
	private void arrived(LedgerPeer peer, BlockRequest request, BlockBatch batch, Throwable failure) {
		hooks.control().accept(() -> {
			if (failure != null || batch == null) {
				discredit(peer);
			} else if (supervisor.ready()) {
				take(peer, request, batch);
			} else {
				abandon(peer);
			}
		});
	}

	private void take(LedgerPeer peer, BlockRequest request, BlockBatch batch) {
		final long before = supervisor.ledger().height();

		if (!answers(request, batch)) {
			blame(peer);
			abandon(peer);
		} else if (applyAll(peer, batch)) {
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

	private boolean applyAll(LedgerPeer peer, BlockBatch batch) {
		return batch.blocks().stream().allMatch(block -> apply(peer, block));
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
			return true;
		}

		final Optional<List<Action>> actions = supervisor.itemCall(peer, Kind.BLOCK_BATCH,
			node -> node.onMessage(new Decision(block)));

		actions.ifPresent(this::carryOutSynced);
		return actions.isPresent() && movedUpByOne(before, peer);
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
	private void carryOn(LedgerPeer peer, BlockBatch batch, long before) {
		final long height = supervisor.ledger().height();

		if (batch.blocks().isEmpty()) {
			limiter.misbehaved(peer.id(), EMPTY_BATCH_COST);
			discredit(peer);
		} else if (height == before) {
			abandon(peer);
		} else if (sync.claimsAbove(peer.id(), height, clock.getAsLong())) {
			hooks.demote().accept(height - before);
			continueOrConclude(peer);
		} else {
			conclude();
		}
	}

	/* A demotion that could not build the observer has halted the driver, and then there is no one to ask */
	private void continueOrConclude(LedgerPeer peer) {
		if (supervisor.ready()) {
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

	/* The peers are told once where the ledger stands, not for every batch, as they limit the statuses they take */
	private void conclude() {
		final long began = sync.end();

		if (supervisor.ready() && supervisor.ledger().height() > began) {
			hooks.sendStatus().run();
		}

		hooks.promote().run();
		startSync();
	}

	/* A block that contradicts the checkpoint, or a second certified block at a height the ledger holds, halts the
	   driver before it is applied. A peer that sends one that is not certified is wrong and no more. */
	boolean vet(Block block) {
		final Optional<String> reason = ChainGuard.contradiction(supervisor.ledger(), options, block);

		reason.ifPresent(supervisor::halt);
		return reason.isEmpty();
	}
}
