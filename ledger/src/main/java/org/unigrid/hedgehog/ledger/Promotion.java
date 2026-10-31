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

import java.util.Arrays;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/* What the statuses of the peers do to the vote of this node. A node with a key starts as an observer and votes only
   once the grace period is over, so that the peers have had time to be heard, and it has caught up with the claims
   that stand; a node whose sign state was lost then cannot sign at a height the network has left. A status is a claim
   and proves nothing: no single one of them, a low one least, can bring the vote forward, and a peer that claims
   more than it gives holds the vote back for no longer than one unanswered request. After the grace a claim holds
   the vote back only while a request to its peer waits for an answer or its last batch moved the ledger. A node that
   votes is never moved out of voting: it fetches what it lacks and votes at the tip, and the sign state is what
   keeps it from signing twice at a height. Used on the serial executor only. */
final class Promotion {
	private static final int OTHER_CHAIN_COST = 10;

	private final NodeSupervisor supervisor;
	private final SyncState sync;
	private final PeerLimiter limiter;
	private final LongSupplier clock;
	private final boolean hasKey;
	private final Runnable startSync;
	private boolean graceElapsed;

	/* `startSync` is what a status may start */
	Promotion(NodeSupervisor supervisor, SyncState sync, PeerLimiter limiter, LongSupplier clock, boolean hasKey,
		Runnable startSync) {
		this.supervisor = supervisor;
		this.sync = sync;
		this.limiter = limiter;
		this.clock = clock;
		this.hasKey = hasKey;
		this.startSync = startSync;
	}

	/* A status is a claim of where the chain of a peer stands, and proves nothing: it tells this node whom to ask for
	   blocks, and gates the vote of a node that has not voted yet. A peer of another chain is no part of this network.
	   A peer that could not back its claim is not heard for a while, and one whose tip is not the block this node
	   holds at that height is on another fork, which only certified blocks can settle, so it counts for nothing and
	   costs nothing. The sync is started before the vote is looked at, so that a claim that is asked about holds the
	   vote back while it is. */
	void heard(LedgerPeer from, Status status) {
		final long now = clock.getAsLong();
		final Ledger ledger = supervisor.ledger();

		if (!Arrays.equals(status.chainId(), ledger.chainId())) {
			limiter.misbehaved(from.id(), OTHER_CHAIN_COST);
		} else if (!sync.discredited(from.id(), now) && !ChainGuard.contradictsHeld(ledger, status)) {
			sync.heard(from, status.height(), now);
			startSync.run();
			promoteIfCaughtUp();
		}
	}

	void promoteIfCaughtUp() {
		if (mayPromote() && caughtUp()) {
			supervisor.promote();
		}
	}

	/* An observer with a key, whose last promotion that failed is not too recent */
	private boolean mayPromote() {
		return hasKey && supervisor.ready() && !supervisor.node().signs() && supervisor.promotionDue();
	}

	/* The grace period is over, and no claim above the ledger stands, or none that a request waits on or a batch
	   moved the ledger for: one that gets no answer, or none that is right, does not hold the vote back after it */
	private boolean caughtUp() {
		final OptionalLong highest = sync.highest(clock.getAsLong());

		return graceElapsed && (highest.isEmpty() || supervisor.height() >= highest.getAsLong()
			|| !sync.holdsBack());
	}

	void endGrace() {
		graceElapsed = true;
		promoteIfCaughtUp();
	}
}
