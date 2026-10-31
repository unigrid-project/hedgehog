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

package org.unigrid.hedgehog.model.storage.store;

import java.io.IOException;
import java.util.TreeMap;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.Holding;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.Tier;

/* The bytes a store's fragments and tombstones charge against its limits, and the order in which extra fragments
   give way to make room. It has no lock of its own, so the store only calls it while holding its own. */
final class Quota {
	/* Every entry costs a file, an inode and a heap entry whatever its size, so charging at least this much lets
	   the quota bound how many entries there are as well as how many bytes they hold */
	static final long MIN_ENTRY_COST = 4096;
	static final int TOMBSTONE_BUDGET_PERCENT = 1;

	private static final int PERCENT = 100;

	private final TreeMap<Long, GroupId> extrasByAge = new TreeMap<>();
	private long usedBytes;
	private long extraBytes;
	private long maxBytes;
	private int extraPoolPercent;

	interface Eviction {
		void evict(GroupId id) throws IOException;
	}

	void limits(final long maxBytes, final int extraPoolPercent) {
		this.maxBytes = maxBytes;
		this.extraPoolPercent = extraPoolPercent;
	}

	long usedBytes() {
		return usedBytes;
	}

	long extraBytes() {
		return extraBytes;
	}

	void charge(final GroupId id, final Holding holding) {
		usedBytes += costOf(holding.getSize());

		if (holding.getTier() == Tier.EXTRA) {
			extraBytes += costOf(holding.getSize());
			extrasByAge.put(holding.getSequence(), id);
		}
	}

	void release(final Holding holding) {
		usedBytes -= costOf(holding.getSize());

		if (holding.getTier() == Tier.EXTRA) {
			extraBytes -= costOf(holding.getSize());
			extrasByAge.remove(holding.getSequence());
		}
	}

	void chargeTombstone() {
		usedBytes += MIN_ENTRY_COST;
	}

	void releaseTombstone() {
		usedBytes -= MIN_ENTRY_COST;
	}

	boolean admitsTombstone(final int tombstones, final Eviction eviction) throws IOException {
		return (tombstones + 1) * MIN_ENTRY_COST <= percentOf(maxBytes, TOMBSTONE_BUDGET_PERCENT)
			&& makeRoom(Tier.GUARANTEED, MIN_ENTRY_COST, eviction);
	}

	/* Evicts the oldest extra fragments until an entry of this size fits, or refuses at once when it never can */
	boolean makeRoom(final Tier tier, final long size, final Eviction eviction) throws IOException {
		final long cost = costOf(size);
		final long room = maxBytes - (usedBytes - extraBytes);
		final long ceiling = tier == Tier.GUARANTEED ? room : Math.min(room, extraLimit());

		if (cost > ceiling) {
			return false;
		}

		while (!fits(tier, cost)) {
			eviction.evict(extrasByAge.firstEntry().getValue());
		}

		return true;
	}

	private static long costOf(final long size) {
		return Math.max(size, MIN_ENTRY_COST);
	}

	private long extraLimit() {
		return percentOf(maxBytes, extraPoolPercent);
	}

	/* Splits off the whole hundreds first, so even a quota near Long.MAX_VALUE cannot overflow */
	private static long percentOf(final long amount, final int percent) {
		final int bounded = Math.max(0, Math.min(percent, PERCENT));
		return amount / PERCENT * bounded + amount % PERCENT * bounded / PERCENT;
	}

	private boolean fits(final Tier tier, final long size) {
		final boolean fitsTotal = usedBytes + size <= maxBytes;
		return tier == Tier.GUARANTEED ? fitsTotal : fitsTotal && extraBytes + size <= extraLimit();
	}
}
