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
import lombok.extern.slf4j.Slf4j;

/* What a node passes on and what it holds against whoever sent it. Only what the node took in as new is passed on,
   and never back to where it came from, so a message that comes round again is no longer new and two honest nodes
   cannot send one to each other for ever.

   A sender is charged only for what no honest node would relay: a transaction or a message that is provably forged.
   Everything else depends on the state or the policy of the node that receives it, and honest nodes differ in both:
   a validator that signs two ballots for one slot gives some honest nodes the one and some the other, and each
   relays what it holds to the others, who hold the second word and refuse the first. Were that charged, honest
   nodes would ban each other. Used by the driver on its serial executor only. */
@Slf4j
final class Gossip {
	static final int TRANSACTION_COST = 1;
	static final int MESSAGE_COST = 2;

	/* What the node did with an item: the actions it asks for, why it refused the item or empty if it took it in,
	   whether to pass it on and what the sender owes for it */
	record Receipt(List<Action> actions, Optional<String> rejection, boolean relay, int cost) {
	}

	private final LedgerNetwork network;

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

	/* A duplicate, a nonce that is used or too far ahead, a balance that is short, a fee below a node's policy and a
	   full pool all depend on the node and cost nothing. A signature that does not verify is the same for every
	   node, and no honest one relays it. */
	Receipt transaction(LedgerNode node, Transaction transaction) throws IOException {
		final Optional<String> rejection = node.submit(transaction);

		if (rejection.isPresent()) {
			final boolean forged = forged(transaction, node.ledger().chainId());

			return new Receipt(List.of(), rejection, false, forged ? TRANSACTION_COST : 0);
		}

		return new Receipt(node.onWork(), rejection, true, 0);
	}

	static boolean forged(Transaction transaction, byte[] chainId) {
		return switch (transaction) {
			case Transfer transfer -> !transfer.hasValidSignature(chainId);
			case Claim claim -> !claim.hasValidSignature(chainId);
			case Vote vote -> !vote.hasValidSignature(chainId);
			case Mint mint -> false;
		};
	}

	Receipt heartbeat(LedgerNode node, Heartbeat heartbeat) {
		final Optional<String> rejection = node.submit(heartbeat);

		return new Receipt(List.of(), rejection, rejection.isEmpty(), 0);
	}

	/* New is what the engine did not hold and does hold. A message that took the node on to the next height is not
	   held any more, and the engine says that to the peers itself, with the decision. Only a message of the current
	   height that the node did not take is looked at, and it costs the sender only when it is provably forged: a
	   peer that is ahead or behind sends the other heights, and a second word of an equivocator or a ballot far
	   ahead of the view is not held by the node and relayed by honest ones all the same. */
	Receipt message(LedgerNode node, Message message) throws IOException {
		final boolean fresh = !node.holds(message);
		final long height = node.height();
		final List<Action> actions = node.onMessage(message);
		final boolean moved = node.height() > height;
		final boolean held = node.holds(message);
		final boolean unheld = fresh && !held && !moved && heightOf(message) == height;

		return new Receipt(actions, Optional.empty(), fresh && held, costOf(node.ledger(), message, unheld));
	}

	private static int costOf(Ledger ledger, Message message, boolean unheld) {
		return unheld && forged(ledger, message) ? MESSAGE_COST : 0;
	}

	private static long heightOf(Message message) {
		return switch (message) {
			case Ballot ballot -> ballot.height();
			case Proposal proposal -> proposal.height();
			case Decision decision -> decision.block().getHeight();
		};
	}

	/* A decision of the current height that the node did not take was refused by the check of its certificate, which
	   asks no policy of the node and is the same for every honest one */
	private static boolean forged(Ledger ledger, Message message) {
		return switch (message) {
			case Ballot ballot -> forged(ledger, ballot);
			case Proposal proposal -> forged(ledger, proposal);
			case Decision decision -> true;
		};
	}

	private static boolean forged(Ledger ledger, Ballot ballot) {
		return !ledger.validators().contains(ballot.validator()) || !ballot.hasValidSignature(ledger.chainId());
	}

	private static boolean forged(Ledger ledger, Proposal proposal) {
		return !proposal.sender().equals(ledger.validators().proposerAt(proposal.height(), proposal.view()))
			|| !proposal.hasValidSignature(ledger.chainId());
	}
}
