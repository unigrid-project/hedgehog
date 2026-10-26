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

package org.unigrid.hedgehog.s3.cache;

import java.time.Duration;
import java.time.Instant;

/* A hit count that halves every half-life, decayed whenever it is looked at instead of on a timer */
record Popularity(double hits, Instant at) {
	Popularity decayed(final Instant now, final Duration halfLife) {
		if (!now.isAfter(at)) {
			return this;
		}

		final double halfLives = (double) Duration.between(at, now).toNanos() / halfLife.toNanos();
		return new Popularity(hits * Math.pow(0.5, halfLives), now);
	}

	Popularity hit(final Instant now, final Duration halfLife) {
		final Popularity decayed = decayed(now, halfLife);
		return new Popularity(decayed.hits() + 1, decayed.at());
	}
}
