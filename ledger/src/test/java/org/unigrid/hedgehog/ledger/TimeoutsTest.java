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

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class TimeoutsTest {
	private static final Timeouts TIMEOUTS = new Timeouts(100, 10, 200, 20, 300, 30);

	@Example
	public void shouldWaitTheBasePlusOneStepPerView() {
		assertThat(TIMEOUTS.propose(0), equalTo(100L));
		assertThat(TIMEOUTS.propose(4), equalTo(140L));
		assertThat(TIMEOUTS.prevote(0), equalTo(200L));
		assertThat(TIMEOUTS.prevote(4), equalTo(280L));
		assertThat(TIMEOUTS.precommit(0), equalTo(300L));
		assertThat(TIMEOUTS.precommit(4), equalTo(420L));
	}

	@Example
	public void shouldWaitLongerForEachStepOfTheDefaultsAsTheViewsGoOn() {
		assertThat(Timeouts.DEFAULT.propose(0), equalTo(3000L));
		assertThat(Timeouts.DEFAULT.propose(1), equalTo(3500L));
		assertThat(Timeouts.DEFAULT.propose(6), equalTo(6000L));
		assertThat(Timeouts.DEFAULT.prevote(0), equalTo(1000L));
		assertThat(Timeouts.DEFAULT.prevote(1), equalTo(1500L));
		assertThat(Timeouts.DEFAULT.prevote(6), equalTo(4000L));
		assertThat(Timeouts.DEFAULT.precommit(0), equalTo(1000L));
		assertThat(Timeouts.DEFAULT.precommit(1), equalTo(1500L));
		assertThat(Timeouts.DEFAULT.precommit(6), equalTo(4000L));
	}

	@Property(tries = 100)
	public void shouldGrowWithEveryViewUpToTheLastGrowingView(@ForAll @LongRange(min = 1, max = 100_000) long base,
		@ForAll @LongRange(min = 1, max = 100_000) long step,
		@ForAll @IntRange(min = 0, max = Timeouts.LAST_GROWING_VIEW - 1) int view) {
		final Timeouts timeouts = new Timeouts(base, step, base, step, base, step);

		assertThat(timeouts.propose(view + 1), greaterThan(timeouts.propose(view)));
		assertThat(timeouts.prevote(view + 1), greaterThan(timeouts.prevote(view)));
		assertThat(timeouts.precommit(view + 1), greaterThan(timeouts.precommit(view)));
	}

	/* A step stops growing at the last growing view, or once it waits half a minute if that comes later */
	@Property(tries = 200)
	public void shouldStopGrowingAtTheLastGrowingViewOrAtHalfAMinuteWhicheverComesLater(
		@ForAll @LongRange(min = 1, max = 100_000) long base, @ForAll @LongRange(min = 1, max = 100_000) long step,
		@ForAll @IntRange(min = Timeouts.LAST_GROWING_VIEW) int view) {
		final Timeouts timeouts = new Timeouts(base, step, base, step, base, step);
		final long last = timeouts.propose(Timeouts.LAST_GROWING_VIEW);
		final long longest = timeouts.propose(Integer.MAX_VALUE);
		final long halfAMinute = Timeouts.GROWS_TO_MILLIS;
		final int next = view == Integer.MAX_VALUE ? view : view + 1;

		assertThat(longest == last || longest >= halfAMinute && longest - step < halfAMinute, is(true));
		assertThat(timeouts.propose(view), lessThanOrEqualTo(timeouts.propose(next)));
		assertThat(timeouts.propose(view), lessThanOrEqualTo(longest));
		assertThat(timeouts.prevote(view), equalTo(timeouts.propose(view)));
		assertThat(timeouts.precommit(view), equalTo(timeouts.propose(view)));
	}

	@Example
	public void shouldLetShortTimeoutsGrowUntilTheyWaitHalfAMinute() {
		final Timeouts quick = new Timeouts(10, 1, 20, 2, 30, 3);

		assertThat(quick.propose(1000), equalTo(1010L));
		assertThat(quick.propose(29_990), equalTo(30_000L));
		assertThat(quick.propose(Integer.MAX_VALUE), equalTo(30_000L));
		assertThat(quick.prevote(Integer.MAX_VALUE), equalTo(30_000L));
		assertThat(quick.precommit(Integer.MAX_VALUE), equalTo(30_000L));
	}

	@Example
	public void shouldWaitHalfAMinuteAtMostForAStepOfTheDefaults() {
		assertThat(Timeouts.DEFAULT.propose(Timeouts.LAST_GROWING_VIEW), equalTo(28_000L));
		assertThat(Timeouts.DEFAULT.propose(Integer.MAX_VALUE), equalTo(30_000L));
		assertThat(Timeouts.DEFAULT.prevote(Integer.MAX_VALUE), equalTo(30_000L));
		assertThat(Timeouts.DEFAULT.precommit(Integer.MAX_VALUE), equalTo(30_000L));
	}

	@Example
	public void shouldRefuseABaseOrAStepOfZeroOrLess() {
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(0, 10, 200, 20, 300, 30));
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(100, 0, 200, 20, 300, 30));
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(100, 10, 0, 20, 300, 30));
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(100, 10, 200, 0, 300, 30));
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(100, 10, 200, 20, 0, 30));
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(100, 10, 200, 20, 300, 0));
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(-1, 10, 200, 20, 300, 30));
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(100, -10, 200, 20, 300, 30));
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(100, 10, -200, 20, 300, 30));
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(100, 10, 200, -20, 300, 30));
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(100, 10, 200, 20, -300, 30));
		assertThrows(IllegalArgumentException.class, () -> new Timeouts(100, 10, 200, 20, 300, -30));
	}
}
