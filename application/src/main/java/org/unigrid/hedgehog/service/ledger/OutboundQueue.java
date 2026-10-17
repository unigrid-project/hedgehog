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

package org.unigrid.hedgehog.service.ledger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.unigrid.hedgehog.ledger.Ballot;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Status;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.network.Packet;

/* What waits to be written to one stream while the stream takes no more. A stream takes about as much as its window
   allows at a time, which at the start of a connection is less than one block, and what a stream cannot take is not
   lost but waits here, in two lanes. The votes, which the others wait for to go on, are written before the rest, so
   that a block that fills the window does not hold them up. Each lane is bounded in bytes, so that a stream that is
   never read cannot make this node queue without end: what comes to a full lane is refused, the lane keeps what it
   has. Only the event loop of the stream touches this, and nothing is locked. */
final class OutboundQueue {
	/* A ballot, a heartbeat or a status is some 180 bytes encoded */
	static final int LARGEST_VOTE = 256;
	static final int VOTES_PER_LANE = 512;

	enum Lane {
		VOTES(VOTES_PER_LANE * LARGEST_VOTE),

		/* Two of the largest messages that a decoder takes, so that a block can wait behind another */
		BULK(2 * WireCodec.MAX_MESSAGE_SIZE);

		private final int limit;

		Lane(final int limit) {
			this.limit = limit;
		}

		int limit() {
			return limit;
		}

		/* A vote is a ballot, a heartbeat or a status, which are of a fixed size and are what the others wait for;
		   everything else is a block or a transaction */
		static Lane of(final Object wire) {
			return wire instanceof Ballot || wire instanceof Heartbeat || wire instanceof Status ? VOTES : BULK;
		}
	}

	private record Entry(Packet packet, int size) {
	}

	private static final class Waiting {
		private final Deque<Entry> entries = new ArrayDeque<>();
		private int bytes;
	}

	private final Map<Lane, Waiting> lanes = new EnumMap<>(Lane.class);

	OutboundQueue() {
		for (Lane lane : Lane.values()) {
			lanes.put(lane, new Waiting());
		}
	}

	/* The size of the encoding, without its frame: a bound by the kind would count a block of one transaction as the
	   largest block there can be, and a lane would hold no more than two of them */
	static int sizeOf(final Object wire) {
		return switch (wire) {
			case Transaction transaction -> transaction.encode().length;
			case Message message -> WireCodec.encodeMessage(message).length;
			case Heartbeat heartbeat -> WireCodec.HEARTBEAT_SIZE;
			case Status status -> WireCodec.STATUS_SIZE;
			default -> throw new IllegalArgumentException("Not an object of the ledger protocol: " + wire);
		};
	}

	/* False when the lane is full, and then the lane is as it was */
	boolean offer(final Lane lane, final Packet packet, final int size) {
		final Waiting waiting = lanes.get(lane);

		if (waiting.bytes + size > lane.limit()) {
			return false;
		}

		waiting.entries.addLast(new Entry(packet, size));
		waiting.bytes += size;
		return true;
	}

	/* The oldest of the votes, and when there is none the oldest of the rest */
	Optional<Packet> poll() {
		for (Lane lane : Lane.values()) {
			final Waiting waiting = lanes.get(lane);
			final Entry entry = waiting.entries.pollFirst();

			if (entry != null) {
				waiting.bytes -= entry.size();
				return Optional.of(entry.packet());
			}
		}

		return Optional.empty();
	}

	boolean isEmpty() {
		return lanes.values().stream().allMatch(waiting -> waiting.entries.isEmpty());
	}

	int size() {
		return lanes.values().stream().mapToInt(waiting -> waiting.entries.size()).sum();
	}

	void clear() {
		lanes.values().forEach(waiting -> {
			waiting.entries.clear();
			waiting.bytes = 0;
		});
	}
}
