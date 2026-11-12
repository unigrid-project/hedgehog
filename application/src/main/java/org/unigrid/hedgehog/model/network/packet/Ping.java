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

package org.unigrid.hedgehog.model.network.packet;

import io.netty.util.AttributeKey;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.unigrid.hedgehog.model.network.Packet;

@Data
@Builder
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class Ping extends Packet implements Serializable {
	public static final AttributeKey<Long> PING_TIME_KEY = AttributeKey.valueOf("PING_TIME");
	/* Below the 30 seconds after which a NAT, Docker's among them, may forget a UDP flow that is quiet: a node behind
	   one would otherwise answer from another port once the connection had gone quiet, and its peers lose it */
	public static final int HEARTBEAT_SECONDS = 20;

	/* A QUIC stream is reliable, so a ping that gets no answer was not lost: the peer is gone or wedged, as one that
	   crashed sends no close. Three in a row, about a minute, still let through a long pause for garbage collection
	   or a peer that is busy. Anything else that arrives on the stream counts as an answer, as one can wait behind a
	   large frame (PeerActivityHandler). */
	public static final int MISSED_PINGS_BEFORE_CLOSE = 3;
	public static final AttributeKey<Integer> MISSED_PINGS_KEY = AttributeKey.valueOf("MISSED_PINGS");

	/* A stream that takes no more is not written to by the schedules, and its peer is taken for gone as well once it
	   has read too little: a peer that reads nothing fills its stream for good, whatever it sends meanwhile. This
	   much is to leave the stream in a period. What a period is short of that is owed, what it brings above that pays
	   off what is owed and is not kept for later, and the stream is closed once it owes what this many periods are
	   to bring: after about a minute of nothing, and as soon as it is that far behind over any run of periods. Were
	   the periods of too little counted and the count started again by one of enough, a peer that reads this much
	   in every third period would be kept for good. A peer on a slow link is kept however long something large takes
	   to reach it. What is to leave a stream is no rate to keep up with at every moment, as it is bounded by the
	   link of this node as much as by that of the peer.

	   The same holds the other way for what arrives of a frame that is not whole (FrameDecoder), as the stream holds
	   what has arrived of the frame until then. */
	public static final int LEAST_BYTES_A_PERIOD = 16 * 1024;
	public static final int EMPTY_PERIODS_BEFORE_CLOSE = 3;
	public static final long DEBT_BEFORE_CLOSE = (long) EMPTY_PERIODS_BEFORE_CLOSE * LEAST_BYTES_A_PERIOD;
	public static final AttributeKey<Long> DEBT_KEY = AttributeKey.valueOf("DEBT");

	/* What left the stream as whole pieces, and all that had left it when the schedule last ran */
	public static final AttributeKey<Long> TAKEN_BYTES_KEY = AttributeKey.valueOf("TAKEN_BYTES");
	public static final AttributeKey<Long> TAKEN_BEFORE_KEY = AttributeKey.valueOf("TAKEN_BEFORE");

	private boolean response;
	@Builder.Default private long nanoTime = System.nanoTime();

	public Ping() {
		setType(Type.PING);
	}

	/* What a stream owes after one more period, in which this much moved */
	public static long debtAfter(long debt, long moved) {
		return Math.max(0, debt + LEAST_BYTES_A_PERIOD - moved);
	}
}
