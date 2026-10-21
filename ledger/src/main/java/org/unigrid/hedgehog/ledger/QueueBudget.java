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

import java.util.HashMap;
import java.util.Map;

/* What waits in the queue of the driver, in items and in bytes: in all, for each peer, and for the items that are
   junk, which have a tenth of the room in both and so cannot crowd out the rest. An item that does not fit is not
   taken, and what it would have taken is not counted. Any thread may take and give back. */
final class QueueBudget {
	private static final int JUNK_PERCENT = 10;
	private static final int PERCENT = 100;

	private final int items;
	private final long bytes;
	private final int itemsPerPeer;
	private final long bytesPerPeer;
	private final int junkItems;
	private final long junkBytes;
	private final Map<String, long[]> perPeer = new HashMap<>();
	private int queuedItems;
	private long queuedBytes;
	private int queuedJunkItems;
	private long queuedJunkBytes;

	QueueBudget(int items, int itemsPerPeer, long bytes, long bytesPerPeer) {
		this.items = items;
		this.itemsPerPeer = itemsPerPeer;
		this.bytes = bytes;
		this.bytesPerPeer = bytesPerPeer;
		this.junkItems = Math.max(1, items * JUNK_PERCENT / PERCENT);
		this.junkBytes = Math.max(1, bytes * JUNK_PERCENT / PERCENT);
	}

	synchronized boolean take(String peer, long size, boolean junk) {
		final long[] mine = perPeer.getOrDefault(peer, new long[2]);

		if (!fits(mine, size, junk)) {
			return false;
		}

		mine[0]++;
		mine[1] += size;
		perPeer.put(peer, mine);
		queuedItems++;
		queuedBytes += size;
		queuedJunkItems += junk ? 1 : 0;
		queuedJunkBytes += junk ? size : 0;
		return true;
	}

	private boolean fits(long[] mine, long size, boolean junk) {
		return fitsInAll(size) && fitsForPeer(mine, size) && (!junk || fitsInJunk(size));
	}

	private boolean fitsInAll(long size) {
		return queuedItems < items && queuedBytes + size <= bytes;
	}

	private boolean fitsForPeer(long[] mine, long size) {
		return mine[0] < itemsPerPeer && mine[1] + size <= bytesPerPeer;
	}

	private boolean fitsInJunk(long size) {
		return queuedJunkItems < junkItems && queuedJunkBytes + size <= junkBytes;
	}

	synchronized void giveBack(String peer, long size, boolean junk) {
		queuedItems--;
		queuedBytes -= size;
		queuedJunkItems -= junk ? 1 : 0;
		queuedJunkBytes -= junk ? size : 0;
		perPeer.computeIfPresent(peer, (id, mine) -> {
			mine[0]--;
			mine[1] -= size;
			return mine[0] == 0 ? null : mine;
		});
	}

	synchronized int items() {
		return queuedItems;
	}

	synchronized long bytes() {
		return queuedBytes;
	}

	synchronized int peers() {
		return perPeer.size();
	}
}
