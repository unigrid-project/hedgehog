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

import java.util.Set;
import org.unigrid.hedgehog.ledger.PeerLimiter.Kind;

/* Where an item of a peer is sorted before it takes a place in the queue, on the thread of whoever brings it. It
   reads what the driver published after its last turn and nothing else, never the ledger or the limiter, which only
   the serial executor may touch, so what it knows is as old as that turn.

   An item from a peer that is penalised is cut. One is plausible when it is what an honest peer sends of this chain
   now: a message of the engine of this height or the next from a validator, a decision for the height that is
   decided, a heartbeat of a validator, a status of a peer that is known, a transaction (which cannot be told by its
   look) or a request for blocks. The rest is junk, and waits and is counted in shares of its own. */
final class Door {
	enum Admission {
		CUT, PLAUSIBLE, JUNK
	}

	/* The height of the ledger, the keys of the validators of the round, the ids of the peers that are penalised and
	   the ids of the peers that are connected */
	record Snapshot(long height, Set<AccountKey> validators, Set<String> penalised, Set<String> known) {
	}

	private volatile Snapshot snapshot = new Snapshot(0, Set.of(), Set.of(), Set.of());

	void publish(Snapshot next) {
		snapshot = next;
	}

	Snapshot snapshot() {
		return snapshot;
	}

	/* A connection that the application announces is plausible, unless the peer is penalised */
	Admission sortConnection(String peer) {
		return sort(peer, Kind.BLOCK_REQUEST, null);
	}

	Admission sort(String peer, Kind kind, Object wire) {
		final Snapshot now = snapshot;

		if (peer == null) {
			return Admission.JUNK;
		}

		if (now.penalised().contains(peer)) {
			return Admission.CUT;
		}

		return plausible(now, peer, kind, wire) ? Admission.PLAUSIBLE : Admission.JUNK;
	}

	private static boolean plausible(Snapshot now, String peer, Kind kind, Object wire) {
		return switch (kind) {
			case TRANSACTION, BLOCK_REQUEST -> true;
			case HEARTBEAT -> plausibleHeartbeat(now, wire);
			case STATUS -> now.known().contains(peer);
			case CONSENSUS -> plausibleMessage(now, wire);
		};
	}

	private static boolean plausibleHeartbeat(Snapshot now, Object wire) {
		return wire instanceof Heartbeat heartbeat && validator(now, heartbeat.validator());
	}

	private static boolean plausibleMessage(Snapshot now, Object wire) {
		return wire instanceof Message message && plausible(now, message);
	}

	private static boolean plausible(Snapshot now, Message message) {
		return switch (message) {
			case Ballot ballot -> upcoming(now, ballot.height()) && validator(now, ballot.validator());
			case Proposal proposal -> upcoming(now, proposal.height()) && validator(now, proposal.sender());
			case Decision decision -> decision.block().getHeight() == now.height() + 1;
		};
	}

	private static boolean validator(Snapshot now, AccountKey key) {
		return now.validators().contains(key);
	}

	/* The height that is decided now and the one after it */
	private static boolean upcoming(Snapshot now, long height) {
		return height == now.height() + 1 || height == now.height() + 2;
	}
}
