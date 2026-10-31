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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/* What a node passes on and what it holds against whoever sent it. Only what the node took in as new is passed on,
   and never back to where it came from, so a message that comes round again is no longer new and two honest nodes
   cannot send one to each other for ever.

   A sender is charged only for what no honest node would relay: a transaction or a message that is provably forged.
   Everything else depends on the state or the policy of the node that receives it, and honest nodes differ in both:
   a validator that signs two ballots for one slot gives some honest nodes the one and some the other, and each
   relays what it holds to the others, who hold the second word and refuse the first. Were that charged, honest
   nodes would ban each other.

   So the cost schedule is thin on purpose, and it is the buckets of the limiter that brake everything else: a
   peer that keeps sending what no node takes in, such as transfers without funds or ballots far ahead, is cut
   off by the bucket of its kind, and then penalised by the limiter for each refusal that follows. The check of a
   signature that a cost depends on is made only for a message that a bucket let through and the node refused; a
   transaction is charged on the verdict of the ledger and is not checked again. Used by the driver on its serial
   executor only. */
@Slf4j
final class Gossip {
	static final int TRANSACTION_COST = 1;
	static final int MESSAGE_COST = 2;

	/* What the node did with an item: the actions it asks for, why it refused the item or empty if it took it in,
	   whether to pass it on and what the sender owes for it */
	record Receipt(List<Action> actions, Optional<String> rejection, boolean relay, int cost) {
	}

	private final LedgerNetwork network;
	private final int relayFanout;

	Gossip(LedgerNetwork network, int relayFanout) {
		this.network = network;
		this.relayFanout = relayFanout;
	}

	void toAll(Object wire) {
		toOthers(LocalSource.LOCAL, wire);
	}

	/* What the node makes itself goes to every peer. What it passes on goes to no more than `relayFanout` of the
	   others, and to the same ones each time for the same item, whom a hash of the item and the id of each peer picks:
	   a node that passes everything on to everyone makes every one of N nodes send N copies. */
	void toOthers(LedgerPeer from, Object wire) {
		final List<LedgerPeer> others = network.peers().stream()
			.filter(peer -> LocalSource.isLocal(from) || !peer.id().equals(from.id())).toList();

		(LocalSource.isLocal(from) ? others.stream() : closest(others, wire)).forEach(peer -> send(peer, wire));
	}

	private Stream<LedgerPeer> closest(List<LedgerPeer> others, Object wire) {
		if (others.size() <= relayFanout) {
			return others.stream();
		}

		final long key = keyOf(wire);

		return others.stream().sorted(Comparator.comparingLong(peer -> Hashing.rank(key, peer.id())))
			.limit(relayFanout);
	}

	/* A number that is the same for the same item on every node and in every run */
	private static long keyOf(Object wire) {
		return switch (wire) {
			case Transaction transaction -> Arrays.hashCode(transaction.encode());
			case Message message -> keyOf(message);
			case Heartbeat heartbeat -> Arrays.hashCode(heartbeat.signature());
			case Status status -> Arrays.hashCode(status.tipHash());
			default -> 0;
		};
	}

	private static long keyOf(Message message) {
		return switch (message) {
			case Ballot ballot -> Arrays.hashCode(ballot.signature());
			case Proposal proposal -> Arrays.hashCode(proposal.signature());
			case Decision decision -> Arrays.hashCode(decision.block().hash());
		};
	}

	/* A peer that cannot be written to has lost the object, which the protocol makes good by sending again */
	static void send(LedgerPeer peer, Object wire) {
		try {
			peer.send(wire);
		} catch (RuntimeException e) {
			log.atDebug().setCause(e).log("Could not send to {}", peer.id());
		}
	}

	/* A duplicate, a nonce that is used or too far ahead, a balance that is short, a fee below a node's policy and a
	   full pool all depend on the node and cost nothing. A signature that does not verify is the same for every
	   node, and no honest one relays it. The ledger says that it found one in the reason it gives, after a check
	   that its cache of verified signatures spares for a transaction it saw before, so nothing is verified here.
	   The driver tells the node that there may be work once this has taken the transaction in, as a call of its
	   own. */
	Receipt transaction(LedgerNode node, Transaction transaction) {
		final Optional<String> rejection = node.submit(transaction);
		final boolean forged = rejection.filter(LedgerState::isSignatureRejection).isPresent();

		return new Receipt(List.of(), rejection, rejection.isEmpty(), forged ? TRANSACTION_COST : 0);
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
