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
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/* What a node passes on and what it holds against whoever sent it. Only what the node took in as new is passed on,
   and never back to where it came from, so a message that comes round again is no longer new and two honest nodes
   cannot send one to each other for ever. What the node did not take in costs the sender, unless an honest peer
   could have sent it too. Used by the driver on its serial executor only. */
@Slf4j
final class Gossip {
	static final int TRANSACTION_COST = 1;
	static final int MESSAGE_COST = 2;

	/* How many accepted transactions are remembered, in two halves of which the older is dropped when the newer fills */
	private static final int REMEMBERED = 4096;

	/* What the node did with an item: the actions it asks for, why it refused the item or empty if it took it in,
	   whether to pass it on and what the sender owes for it */
	record Receipt(List<Action> actions, Optional<String> rejection, boolean relay, int cost) {
	}

	private final LedgerNetwork network;
	private Set<ByteBuffer> recent = new HashSet<>();
	private Set<ByteBuffer> older = new HashSet<>();

	Gossip(LedgerNetwork network) {
		this.network = network;
	}

	void toAll(Object wire) {
		toOthers(null, wire);
	}

	void toOthers(LedgerPeer from, Object wire) {
		network.peers().stream().filter(peer -> from == null || !peer.id().equals(from.id()))
			.forEach(peer -> send(peer, wire));
	}

	/* A peer that cannot be written to has lost the object, which the protocol makes good by sending again */
	private static void send(LedgerPeer peer, Object wire) {
		try {
			peer.send(wire);
		} catch (RuntimeException e) {
			log.atDebug().setCause(e).log("Could not send to {}", peer.id());
		}
	}

	/* A transaction that is turned away costs the sender, unless this node took it in before: a duplicate or a copy
	   that comes after its block is what every honest peer sends now and then. The ledger gives its reasons in words
	   only, which do not tell those from a transaction that is invalid. */
	Receipt transaction(LedgerNode node, Transaction transaction) throws IOException {
		final Optional<String> rejection = node.submit(transaction);
		final ByteBuffer id = ByteBuffer.wrap(transaction.id());

		if (rejection.isPresent()) {
			return new Receipt(List.of(), rejection, false, known(id) ? 0 : TRANSACTION_COST);
		}

		remember(id);
		return new Receipt(node.onWork(), rejection, true, 0);
	}

	Receipt heartbeat(LedgerNode node, Heartbeat heartbeat) {
		final Optional<String> rejection = node.submit(heartbeat);

		return new Receipt(List.of(), rejection, rejection.isEmpty(), 0);
	}

	/* New is what the engine did not hold and does hold. A message that took the node on to the next height is not
	   held any more, and the engine says that to the peers itself, with the decision. A message of another height is
	   not the sender's fault: a peer that is ahead or behind sends them. */
	Receipt message(LedgerNode node, Message message) throws IOException {
		final boolean fresh = !node.holds(message);
		final long height = node.height();
		final List<Action> actions = node.onMessage(message);
		final boolean moved = node.height() > height;
		final boolean held = node.holds(message);
		final boolean invalid = fresh && !held && !moved && heightOf(message) == height;

		return new Receipt(actions, Optional.empty(), fresh && held, invalid ? MESSAGE_COST : 0);
	}

	private static long heightOf(Message message) {
		return switch (message) {
			case Ballot ballot -> ballot.height();
			case Proposal proposal -> proposal.height();
			case Decision decision -> decision.block().getHeight();
		};
	}

	private boolean known(ByteBuffer id) {
		return recent.contains(id) || older.contains(id);
	}

	private void remember(ByteBuffer id) {
		if (recent.size() >= REMEMBERED) {
			older = recent;
			recent = new HashSet<>();
		}

		recent.add(id);
	}
}
