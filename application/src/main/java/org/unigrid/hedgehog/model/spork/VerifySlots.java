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

package org.unigrid.hedgehog.model.spork;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.SporkLag.Pool;

/**
* Pays for the checks of signatures that the sporks from peers cause, which are costly and which anyone can cause. A
* source pays for each check as it is made, from a bucket of its own for the type, which fills by the share of its pool
* a second and holds no more than that, and from one of its own for every type ({@link #accountShareOf}), so that a
* source that sends every type at once draws no more than that. The checks of a second are held to a ceiling for each
* type and to one for the node, and of each ceiling the peers that this node dialed itself ({@link Pool#TRUSTED})
* share three quarters, every other peer the quarter left: anyone can open streams to a node from as many addresses as
* they like, and so spend that quarter, while the peers that a node dials are of its own choosing, one in each network
* group. The handler names a source by its network group, so that the addresses of one network draw one share.
*
* <p>A second is one of the monotonic clock, so that no clock that is set back opens a second again, and what a
* genuine spork was charged is given back to the second it was charged in only. Only a bucket that is not full is
* remembered, so what is held never exceeds the sources that paid while their buckets fill again.
*/
final class VerifySlots {
	/* A check of a P-521 signature takes about a millisecond of one core. A node spends at most a quarter of a core on
	   checking what peers send it, and the sporks of one type at most half of that, so that a flood of one type leaves
	   the others room. */
	static final int NODE_CEILING = 256;
	static final int TYPE_CEILING = 128;

	private static final int QUARTERS = 4;
	private static final int TRUSTED_QUARTERS = 3;
	private static final int TRUSTED_SHARES = 8;
	private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

	/* What it takes to spend what a pool may spend on the node in a second, whatever types they send */
	private static final int ACCOUNTS_TO_SPEND_A_POOL = 8;

	/* The bucket of a source for a type, or for every type when the type is none */
	private record Slot(Type type, Pool pool, String source) {
		private static Slot ofEveryType(final Pool pool, final String source) {
			return new Slot(null, pool, source);
		}

		private int share() {
			return Objects.isNull(type) ? accountShareOf(pool) : shareOf(pool);
		}
	}

	private record Window(long second, int taken) {
	}

	private record Bucket(double checks, long at) {
		private Bucket filledUntil(final long now, final int share) {
			final double filled = (double) Math.max(0, now - at) * share / NANOS_PER_SECOND;

			return new Bucket(Math.min(share, checks + filled), now);
		}
	}

	private final LongSupplier nanos;
	private final Map<Slot, Bucket> buckets = new HashMap<>();
	private final Map<Type, Map<Pool, Window>> typeWindows = new EnumMap<>(Type.class);
	private final Map<Pool, Window> nodeWindows = new EnumMap<>(Pool.class);
	private long sweptIn = Long.MIN_VALUE;

	VerifySlots(final LongSupplier nanos) {
		this.nanos = nanos;
	}

	/* What the peers of a pool may spend of a ceiling */
	static int ceilingOf(final Pool pool, final int ceiling) {
		return pool == Pool.TRUSTED ? ceiling * TRUSTED_QUARTERS / QUARTERS : ceiling / QUARTERS;
	}

	/* What the bucket of a source fills by in a second and holds at most. A source is a network group, or a peer that
	   an operator configured, and draws an eighth of what the dialed peers spend on a type, so that an attacker has to
	   hold eight of the networks that a node dials to hold up the others, and a quarter of what every other peer
	   spends, so that a group that floods leaves three quarters to the rest. */
	static int shareOf(final Pool pool) {
		return ceilingOf(pool, TYPE_CEILING) / (pool == Pool.TRUSTED ? TRUSTED_SHARES : QUARTERS);
	}

	/* What the bucket of a source for every type fills by in a second and holds at most, which every check that it
	   pays for draws from as well: an eighth of what its pool may spend on the node, so that a source that floods every
	   type at once holds up no other, and it takes eight sources to spend that */
	static int accountShareOf(final Pool pool) {
		return ceilingOf(pool, NODE_CEILING) / ACCOUNTS_TO_SPEND_A_POOL;
	}

	/* Whether the source can pay for one check more, and its pool has one left in this second */
	synchronized boolean hasLeft(final Type type, final Pool pool, final String source) {
		return hasLeft(type, pool, source, nanos.getAsLong());
	}

	private boolean hasLeft(final Type type, final Pool pool, final String source, final long now) {
		return bucketOf(new Slot(type, pool, source), now).checks() >= 1
			&& bucketOf(Slot.ofEveryType(pool, source), now).checks() >= 1 && hasRoom(type, pool, secondOf(now));
	}

	/* One check, paid for by the source, or nothing at all; the second it was paid in, which is the one whose ceiling
	   it was judged by, as the clock is read once. The lock is held for the payment only, never while a signature is
	   checked. */
	synchronized OptionalLong take(final Type type, final Pool pool, final String source) {
		final long now = nanos.getAsLong();
		final long second = secondOf(now);

		sweep(second, now);

		if (!hasLeft(type, pool, source, now)) {
			return OptionalLong.empty();
		}

		for (Slot slot : List.of(new Slot(type, pool, source), Slot.ofEveryType(pool, source))) {
			buckets.put(slot, new Bucket(bucketOf(slot, now).checks() - 1, now));
		}

		windowsOf(type).put(pool, new Window(second, windowOf(windowsOf(type), pool, second).taken() + 1));
		nodeWindows.put(pool, new Window(second, windowOf(nodeWindows, pool, second).taken() + 1));
		return OptionalLong.of(second);
	}

	/* What was paid for a genuine spork is given back to its source whole, so that a peer that sends several in a row
	   has each of them checked, and to the second it was paid in, as long as that second lasts: a second that has
	   passed holds no checks to give back, and the one after it holds none of them */
	synchronized void giveBack(final Type type, final Pool pool, final String source, final int checks,
		final long paidIn, final int checksOfThatSecond) {

		final long now = nanos.getAsLong();

		for (Slot slot : List.of(new Slot(type, pool, source), Slot.ofEveryType(pool, source))) {
			buckets.put(slot, new Bucket(Math.min(slot.share(), bucketOf(slot, now).checks() + checks), now));
		}

		if (secondOf(now) == paidIn) {
			lessen(windowsOf(type), pool, paidIn, checksOfThatSecond);
			lessen(nodeWindows, pool, paidIn, checksOfThatSecond);
		}
	}

	synchronized int tracked() {
		return buckets.size();
	}

	/* What the bucket of a source holds now; a source that has not paid since its bucket filled holds the most */
	synchronized double left(final Type type, final Pool pool, final String source) {
		return bucketOf(new Slot(type, pool, source), nanos.getAsLong()).checks();
	}

	private boolean hasRoom(final Type type, final Pool pool, final long second) {
		return windowOf(windowsOf(type), pool, second).taken() < ceilingOf(pool, TYPE_CEILING)
			&& windowOf(nodeWindows, pool, second).taken() < ceilingOf(pool, NODE_CEILING);
	}

	private Bucket bucketOf(final Slot slot, final long now) {
		final Bucket bucket = buckets.get(slot);

		return Objects.isNull(bucket) ? new Bucket(slot.share(), now) : bucket.filledUntil(now, slot.share());
	}

	private Map<Pool, Window> windowsOf(final Type type) {
		return typeWindows.computeIfAbsent(type, key -> new EnumMap<>(Pool.class));
	}

	private static Window windowOf(final Map<Pool, Window> windows, final Pool pool, final long second) {
		final Window window = windows.get(pool);

		return Objects.isNull(window) || window.second() != second ? new Window(second, 0) : window;
	}

	private static void lessen(final Map<Pool, Window> windows, final Pool pool, final long second, final int checks) {
		windows.computeIfPresent(pool, (key, window) -> window.second() == second
			? new Window(second, Math.max(0, window.taken() - checks)) : window);
	}

	private void sweep(final long second, final long now) {
		if (second != sweptIn) {
			buckets.entrySet().removeIf(entry -> entry.getValue().filledUntil(now, entry.getKey().share())
				.checks() >= entry.getKey().share());
			sweptIn = second;
		}
	}

	private static long secondOf(final long now) {
		return Math.floorDiv(now, NANOS_PER_SECOND);
	}
}
