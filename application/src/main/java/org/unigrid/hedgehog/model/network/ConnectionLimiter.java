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
import java.util.HashMap;
import java.util.Map;

/* How many connections one address may hold to this node at a time. A node connects once to each peer it knows and
   the peer once back, so a few more than that leave room for a restart before the old connection has timed out; an
   address that opens more is after the memory and the streams of the node. The retry token proves that an address
   is real before it gets this far. The loopback address is left alone: a forwarder on this host hands every
   connection to the node from there, and nodes of a private network on one host all connect from it. */
public final class ConnectionLimiter {
	public static final int MAX_PER_ADDRESS = 8;

	/* How many connections all addresses may hold at a time. Each address has a share of its own, so many addresses
	   have many shares, and what they hold together is what the memory of the node has to bear. A node knows no more
	   than Topology.MAX_NODES nodes, connects once to each and each once back. */
	public static final int MAX_IN_ALL = 2 * Topology.MAX_NODES;

	private final Map<InetAddress, Integer> held = new HashMap<>();
	private int heldInAll;

	public synchronized boolean admit(InetAddress address) {
		if (address.isLoopbackAddress()) {
			return true;
		}

		final int count = held.getOrDefault(address, 0);

		if (heldInAll >= MAX_IN_ALL || count >= MAX_PER_ADDRESS) {
			return false;
		}

		held.put(address, count + 1);
		heldInAll++;
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
