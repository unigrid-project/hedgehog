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
   network is well behaved a view eventually lasts long enough to finish, up to the last view that grows: the views
   after it wait as long as it does. */
public record Timeouts(long proposeBase, long proposeStep, long prevoteBase, long prevoteStep, long precommitBase,
	long precommitStep) {
	public static final Timeouts DEFAULT = new Timeouts(3000, 500, 1000, 500, 1000, 500);

	/* By this view the steps of the defaults wait 26 to 28 seconds, many times what a full block needs to cross a
	   network that works, even over slow links. Growing further would only make a transaction that turns up after a
	   long run of failed views wait minutes for the view in progress to end. */
	public static final int LAST_GROWING_VIEW = 50;

	public Timeouts {
		if (LongStream.of(proposeBase, proposeStep, prevoteBase, prevoteStep, precommitBase, precommitStep)
			.anyMatch(value -> value <= 0)) {
			throw new IllegalArgumentException("Every base and step of a timeout is above 0");
		}
	}

	public long propose(int view) {
		return proposeBase + proposeStep * grown(view);
	}

	public long prevote(int view) {
		return prevoteBase + prevoteStep * grown(view);
	}

	public long precommit(int view) {
		return precommitBase + precommitStep * grown(view);
	}

	private static int grown(int view) {
		return Math.min(view, LAST_GROWING_VIEW);
	}
}
