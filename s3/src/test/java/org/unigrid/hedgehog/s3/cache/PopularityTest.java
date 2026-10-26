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
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

public class PopularityTest {
	private static final Duration HALF_LIFE = Duration.ofMinutes(30);
	private static final Instant START = Instant.parse("2026-10-18T12:00:00Z");
	private static final double TOLERANCE = 1e-9;

	@Property
	public void halvesEveryHalfLife(@ForAll @DoubleRange(min = 0, max = 1e6) double hits,
		@ForAll @IntRange(max = 40) int halfLives) {

		final Instant later = START.plus(HALF_LIFE.multipliedBy(halfLives));
		final Popularity decayed = new Popularity(hits, START).decayed(later, HALF_LIFE);

		assertThat(decayed.hits(), closeTo(hits / Math.pow(2, halfLives), hits * TOLERANCE));
		assertThat(decayed.at(), equalTo(later));
	}

	@Property
	public void countsAHitOnTopOfWhatIsLeft(@ForAll @DoubleRange(min = 0, max = 1e6) double hits,
		@ForAll @LongRange(max = 1_000_000) long seconds) {

		final Instant later = START.plusSeconds(seconds);
		final Popularity popularity = new Popularity(hits, START);

		assertThat(popularity.hit(later, HALF_LIFE).hits(),
			closeTo(popularity.decayed(later, HALF_LIFE).hits() + 1, TOLERANCE));
	}

	@Property
	public void staysPutWhenTheClockGoesBack(@ForAll @DoubleRange(min = 0, max = 1e6) double hits,
		@ForAll @LongRange(max = 1_000_000) long seconds) {

		final Popularity popularity = new Popularity(hits, START);

		assertThat(popularity.decayed(START.minusSeconds(seconds), HALF_LIFE), equalTo(popularity));
	}
}
