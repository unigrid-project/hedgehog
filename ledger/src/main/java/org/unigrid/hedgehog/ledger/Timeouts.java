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

import java.util.stream.LongStream;

/* How long a step waits, in milliseconds. Every view waits a little longer than the one before, so that once the
   network is well behaved a view eventually lasts long enough to finish. A step stops growing at the last growing
   view, or once it waits half a minute if that comes later, and the views after wait as long. */
public record Timeouts(long proposeBase, long proposeStep, long prevoteBase, long prevoteStep, long precommitBase,
	long precommitStep) {
	public static final Timeouts DEFAULT = new Timeouts(3000, 500, 1000, 500, 1000, 500);

	/* Half a minute is many times what a full block needs to cross a network that works, even over slow links, so
	   timeouts that start short still grow to a delay that is real; growing further would only make a transaction
	   that turns up after a long run of failed views wait minutes for the view in progress to end. A step that waits
	   longer than that by the last growing view, set so by its operator, stops there. */
	public static final long GROWS_TO_MILLIS = 30_000;
	public static final int LAST_GROWING_VIEW = 50;

	public Timeouts {
		if (LongStream.of(proposeBase, proposeStep, prevoteBase, prevoteStep, precommitBase, precommitStep)
			.anyMatch(value -> value <= 0)) {
			throw new IllegalArgumentException("Every base and step of a timeout is above 0");
		}
	}

	public long propose(int view) {
		return grown(proposeBase, proposeStep, view);
	}

	public long prevote(int view) {
		return grown(prevoteBase, prevoteStep, view);
	}

	public long precommit(int view) {
		return grown(precommitBase, precommitStep, view);
	}

	private static long grown(long base, long step, int view) {
		final long toHalfAMinute = base >= GROWS_TO_MILLIS ? 0 : Math.ceilDiv(GROWS_TO_MILLIS - base, step);

		return base + step * Math.min(view, Math.max(LAST_GROWING_VIEW, toHalfAMinute));
	}
}
