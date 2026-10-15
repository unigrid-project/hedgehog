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
   once it has caught up with the claims that stand, or, when none does, once the grace period is over, so that one
   whose sign state was lost cannot sign at a height the network has left. A status is a claim and proves nothing: it
   tells this node whom to ask for blocks, and gates the vote of a node that has not voted yet. A node that votes
   follows instead when the blocks it was given show that it was more than one behind. Used on the serial executor
   only. */
final class Promotion {
	private static final int OTHER_CHAIN_COST = 10;

	private final NodeSupervisor supervisor;
	private final SyncState sync;
	private final PeerLimiter limiter;
	private final LongSupplier clock;
	private final boolean hasKey;
	private final long cooldownMillis;
	private final Runnable startSync;
	private final Recency demotions = new Recency();
	private boolean graceElapsed;

	/* `startSync` is what a status may start, and the cooldown is the shortest time between two demotions */
	Promotion(NodeSupervisor supervisor, SyncState sync, PeerLimiter limiter, LongSupplier clock, boolean hasKey,
		long cooldownMillis, Runnable startSync) {
		this.supervisor = supervisor;
		this.sync = sync;
		this.limiter = limiter;
		this.clock = clock;
		this.hasKey = hasKey;
		this.cooldownMillis = cooldownMillis;
		this.startSync = startSync;
	}

	/* A status is a claim of where the chain of a peer stands, and proves nothing: it tells this node whom to ask for
	   blocks, and gates the vote of a node that has not caught up, but moves no node out of voting. A peer of another
	   chain is no part of this network. A peer that could not back its claim is not heard for a while, and one whose
	   tip is not the block this node holds at that height is on another fork, which only certified blocks can
	   settle, so it counts for nothing and costs nothing. */
	void heard(LedgerPeer from, Status status) {
		final long now = clock.getAsLong();
		final Ledger ledger = supervisor.ledger();

		if (!Arrays.equals(status.chainId(), ledger.chainId())) {
			limiter.misbehaved(from.id(), OTHER_CHAIN_COST);
		} else if (!sync.discredited(from.id(), now) && !ChainGuard.contradictsHeld(ledger, status)) {
			sync.heard(from, status.height(), now);
			promoteIfCaughtUp();
			startSync.run();
		}
	}

	void promoteIfCaughtUp() {
		if (hasKey && supervisor.ready() && !supervisor.node().signs() && caughtUp()) {
			supervisor.become(true);
		}
	}

	/* At the height of the highest claim that stands, or, when none does, once the grace period is over. A claim stands
	   until it lapses or its peer cannot back it up, so one peer cannot keep a node from voting for longer than that. */
	private boolean caughtUp() {
		final OptionalLong highest = sync.highest(clock.getAsLong());

		return highest.isPresent() ? supervisor.ledger().height() >= highest.getAsLong() : graceElapsed;
	}

	void endGrace() {
		graceElapsed = true;
		promoteIfCaughtUp();
	}

	/* A status is a claim, and only certified blocks are proof. A node that votes has fallen behind when it was given
	   more than one block that the others had decided already, and then it follows until it has caught up. Building a
	   node replays the whole log, so it is done once in a cooldown, even if a peer serves a few blocks and claims a
	   great many. */
	void demoteIfProvenBehind(long applied) {
		final long now = clock.getAsLong();

		if (supervisor.votes() && applied > 1 && !demotions.within(now, cooldownMillis)) {
			demotions.mark(now);
			supervisor.become(false);
		}
	}
}
