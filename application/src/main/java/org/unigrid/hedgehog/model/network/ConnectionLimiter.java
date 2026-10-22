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

	private final Map<InetAddress, Integer> held = new HashMap<>();

	public synchronized boolean admit(InetAddress address) {
		if (address.isLoopbackAddress()) {
			return true;
		}

		final int count = held.getOrDefault(address, 0);

		if (count >= MAX_PER_ADDRESS) {
			return false;
		}

		held.put(address, count + 1);
		return true;
	}

	/* For a connection that was admitted and has closed */
	public synchronized void release(InetAddress address) {
		held.computeIfPresent(address, (key, count) -> count > 1 ? count - 1 : null);
	}

	synchronized int held(InetAddress address) {
		return held.getOrDefault(address, 0);
	}
}
