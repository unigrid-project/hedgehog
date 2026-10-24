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
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

/**
* Pays for the checks of signatures that a spork causes, which are costly and may be caused by anyone. A source, which
* is the peer that sent the spork, pays from a bucket of its own for each type, which holds at most {@link #BURST}
* checks and fills by {@link #SHARE} a second, so a peer that sends forged sporks spends only its own and leaves the
* genuine spork of another peer its checks. Of a type at most {@link #CEILING} checks are paid for in a second whoever
* pays, so that many sources cost a bounded amount of work. A spork is charged each check as it is made, never less.
*
* <p>Only a bucket that is not full is remembered, so what is held never exceeds the sources that paid while their
* buckets fill again, however many sources there are.
*/
final class VerifySlots {
	/* A check of a signature is milliseconds of one core, so a few dozen a second for each type cost nothing that
	   is noticed. A genuine spork is sent by every honest peer again and again, and needs only one of them to
	   be checked. */
	static final int CEILING = 32;

	/* What the bucket of a source fills by in a second, so that one peer that keeps sending leaves three quarters of a
	   second to the others */
	static final int SHARE = CEILING / 4;

	/* What the bucket of a source holds at most */
	static final int BURST = CEILING;

	private static final double MILLIS_PER_SECOND = Duration.ofSeconds(1).toMillis();

	private record Slot(Type type, String source) {
	}

	private record Window(Instant start, int taken) {
	}

	private record Bucket(double checks, Instant at) {
		private Bucket filledUntil(final Instant now) {
			final double filled = Duration.between(at, now).toMillis() * SHARE / MILLIS_PER_SECOND;

			return new Bucket(Math.min(BURST, checks + Math.max(0, filled)), now);
		}

		private boolean isFull() {
			return checks >= BURST;
		}
	}

	private final Supplier<Instant> clock;
	private final Map<Slot, Bucket> buckets = new HashMap<>();
	private final Map<Type, Window> windows = new EnumMap<>(Type.class);
	private Instant sweptAt;

	VerifySlots(final Supplier<Instant> clock) {
		this.clock = clock;
	}

	/* Whether the source can pay for one check more, and the type has one left in this second */
	synchronized boolean hasLeft(final Type type, final String source) {
		final Instant now = clock.get();

		return bucketOf(new Slot(type, source), now).checks() >= 1 && windowOf(type, now).taken() < CEILING;
	}

	/* The lock is held for the payment only, never while a signature is checked. What costs more than the source
	   holds, or than the second has left, is not paid for at all. */
	synchronized boolean take(final Type type, final String source, final int checks) {
		final Instant now = clock.get();
		final Slot slot = new Slot(type, source);
		final Bucket bucket = bucketOf(slot, now);
		final Window window = windowOf(type, now);

		sweep(now);

		if (bucket.checks() >= checks && window.taken() + checks <= CEILING) {
			buckets.put(slot, new Bucket(bucket.checks() - checks, now));
			windows.put(type, new Window(window.start(), window.taken() + checks));
			return true;
		}

		return false;
	}

	/* What was paid for a genuine spork is given back, so a peer that sends several in a row has each of them
	   checked, and only checks that fail cost a peer */
	synchronized void giveBack(final Type type, final String source, final int checks) {
		final Instant now = clock.get();
		final Slot slot = new Slot(type, source);

		buckets.put(slot, new Bucket(Math.min(BURST, bucketOf(slot, now).checks() + checks), now));
		windows.computeIfPresent(type, (key, window) -> new Window(window.start(),
			Math.max(0, window.taken() - checks)));
	}

	synchronized int tracked() {
		return buckets.size();
	}

	/* What the bucket of a source holds now; a source that has not paid since its bucket filled holds the most */
	synchronized double left(final Type type, final String source) {
		return bucketOf(new Slot(type, source), clock.get()).checks();
	}

	private Bucket bucketOf(final Slot slot, final Instant now) {
		final Bucket bucket = buckets.get(slot);

		return Objects.isNull(bucket) ? new Bucket(BURST, now) : bucket.filledUntil(now);
	}

	private Window windowOf(final Type type, final Instant now) {
		final Window window = windows.get(type);

		return Objects.isNull(window) || hasPassed(window.start(), now) ? new Window(now, 0) : window;
	}

	private void sweep(final Instant now) {
		if (hasPassed(sweptAt, now)) {
			buckets.values().removeIf(bucket -> bucket.filledUntil(now).isFull());
			sweptAt = now;
		}
	}

	private static boolean hasPassed(final Instant since, final Instant now) {
		return Objects.isNull(since) || !now.isBefore(since.plus(SporkLag.VERIFY_GAP));
	}
}
