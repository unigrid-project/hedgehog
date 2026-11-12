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

package org.unigrid.hedgehog.model.network;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import lombok.Getter;

/* How many connections one address may hold to this node at a time. A node connects once to each peer it knows and
   the peer once back, so a few more than that leave room for a restart before the old connection has timed out; an
   address that opens more is after the memory and the streams of the node. The retry token proves that an address
   is real before it gets this far. The loopback address is left alone: a forwarder on this host hands every
   connection to the node from there, and nodes of a private network on one host all connect from it. */
public final class ConnectionLimiter {
	public static final int MAX_PER_ADDRESS = 8;

	/* How many connections all addresses may hold at a time. Each address has a share of its own, so many addresses
	   have many shares, and what they hold together is what the memory of the node has to bear. A node knows no more
	   than Topology.MAX_NODES nodes, connects once to each and each once back. A seed is dialed by every node, known
	   to it or not, so the operator of one may set another ceiling. */
	public static final int MAX_IN_ALL = 2 * Topology.MAX_NODES;

	/* A node that holds all it admits may refuse peers by the thousand, and tells its operator of it this often */
	private static final long NANOS_BETWEEN_WARNINGS = Duration.ofMinutes(1).toNanos();

	/* What became of a connection that asked to be let in. An address that holds its share is refused for that,
	   whatever the node holds in all: no ceiling would let it in. */
	public enum Admission {
		ADMITTED, ADDRESS_FULL, NODE_FULL
	}

	private final Map<InetAddress, Integer> held = new HashMap<>();
	@Getter private final int ceiling;
	private final LongSupplier nanos;
	private int heldInAll;
	private boolean warned;
	private long warnedAt;

	public ConnectionLimiter() {
		this(MAX_IN_ALL);
	}

	public ConnectionLimiter(int ceiling) {
		this(ceiling, System::nanoTime);
	}

	public ConnectionLimiter(int ceiling, LongSupplier nanos) {
		this.ceiling = ceiling;
		this.nanos = nanos;
	}

	public boolean admit(InetAddress address) {
		return decide(address) == Admission.ADMITTED;
	}

	/* Counts the connection when it is admitted, and tells why when it is not */
	public synchronized Admission decide(InetAddress address) {
		if (address.isLoopbackAddress()) {
			return Admission.ADMITTED;
		}

		final int count = held.getOrDefault(address, 0);

		if (count >= MAX_PER_ADDRESS) {
			return Admission.ADDRESS_FULL;
		}

		if (heldInAll >= ceiling) {
			return Admission.NODE_FULL;
		}

		held.put(address, count + 1);
		heldInAll++;
		return Admission.ADMITTED;
	}

	/* Whether the operator is to be told now that the node holds all it admits: with the first connection that it
	   refuses for that, and then once for each minute in which it refuses another. The clock is one that only tells
	   how long ago something was, so its readings are compared by their difference. */
	public synchronized boolean takeWarning() {
		final long now = nanos.getAsLong();

		if (warned && now - warnedAt < NANOS_BETWEEN_WARNINGS) {
			return false;
		}

		warned = true;
		warnedAt = now;
		return true;
	}

	/* For a connection that was admitted and has closed */
	public synchronized void release(InetAddress address) {
		if (held.containsKey(address)) {
			held.computeIfPresent(address, (key, count) -> count > 1 ? count - 1 : null);
			heldInAll--;
		}
	}

	synchronized int held(InetAddress address) {
		return held.getOrDefault(address, 0);
	}
}
