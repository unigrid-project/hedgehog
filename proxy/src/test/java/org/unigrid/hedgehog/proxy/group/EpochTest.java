/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.proxy.group;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class EpochTest {
	@Property(tries = 300)
	public void shouldContainTheTimeItWasTakenAt(@ForAll @LongRange(min = 0, max = 1L << 50) long epochMillis,
		@ForAll @LongRange(min = 60, max = 0xFFFF_FFFFL) long periodSeconds) {
		final Epoch epoch = Epoch.at(epochMillis, periodSeconds);

		assertThat(epoch.startMillis(periodSeconds), lessThanOrEqualTo(epochMillis));
		assertThat(epoch.next().startMillis(periodSeconds), greaterThan(epochMillis));
	}

	@Property(tries = 100)
	public void shouldFollowWholeSeconds(@ForAll @LongRange(min = 0, max = 1L << 50) long epochMillis,
		@ForAll @LongRange(min = 60, max = 86_400) long periodSeconds) {
		assertThat(Epoch.at(epochMillis, periodSeconds).number(), is(epochMillis / 1000 / periodSeconds));
	}

	@Example
	public void shouldTurnAtEveryPeriod() {
		assertThat(Epoch.at(0, 3600), equalTo(new Epoch(0)));
		assertThat(Epoch.at(3_599_999, 3600), equalTo(new Epoch(0)));
		assertThat(Epoch.at(3_600_000, 3600), equalTo(new Epoch(1)));
		assertThat(Epoch.at(1_750_000_000_000L, 3600), equalTo(new Epoch(486_111)));
		assertThat(new Epoch(486_111).startMillis(3600), is(1_749_999_600_000L));
	}

	@Example
	public void shouldFollowOnTheEpochRightAfter() {
		assertThat(new Epoch(0).next(), equalTo(new Epoch(1)));
		assertThat(new Epoch(486_111).next(), equalTo(new Epoch(486_112)));
		assertThat(new Epoch(486_112).startMillis(3600) - new Epoch(486_111).startMillis(3600), is(3_600_000L));
	}

	@Example
	public void shouldEncodeTheNumberAsEightBigEndianBytes() {
		assertThat(new Epoch(0x0102030405060708L).bytes(), equalTo(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
		assertThat(new Epoch(0).bytes(), equalTo(new byte[8]));
	}

	@Example
	public void shouldRefuseAPeriodOutsideTheSporkBounds() {
		Expect.assertThrows(IllegalArgumentException.class, () -> Epoch.at(0, 59));
		Expect.assertThrows(IllegalArgumentException.class, () -> Epoch.at(0, 0x1_0000_0000L));
		Expect.assertThrows(IllegalArgumentException.class, () -> new Epoch(3).startMillis(0));
	}

	@Example
	public void shouldAcceptThePeriodsAtTheSporkBounds() {
		assertThat(Epoch.at(59_999, 60), equalTo(new Epoch(0)));
		assertThat(Epoch.at(60_000, 60), equalTo(new Epoch(1)));
		assertThat(Epoch.at(0xFFFF_FFFFL * 1000 - 1, 0xFFFF_FFFFL), equalTo(new Epoch(0)));
		assertThat(Epoch.at(0xFFFF_FFFFL * 1000, 0xFFFF_FFFFL), equalTo(new Epoch(1)));
		assertThat(new Epoch(1).startMillis(60), is(60_000L));
		assertThat(new Epoch(1).startMillis(0xFFFF_FFFFL), is(0xFFFF_FFFFL * 1000));
	}

	@Example
	public void shouldRefuseTheStartOfAnEpochOutsideTheSporkBounds() {
		Expect.assertThrows(IllegalArgumentException.class, () -> new Epoch(1).startMillis(59));
		Expect.assertThrows(IllegalArgumentException.class, () -> new Epoch(1).startMillis(0x1_0000_0000L));
	}

	@Example
	public void shouldRefuseTimeBeforeTheUnixEpoch() {
		Expect.assertThrows(IllegalArgumentException.class, () -> Epoch.at(-1, 60));
		Expect.assertThrows(IllegalArgumentException.class, () -> new Epoch(-1));
	}

	@Example
	public void shouldRefuseAnEpochThatDoesNotFitALong() {
		Expect.assertThrows(IllegalArgumentException.class, () -> new Epoch(Long.MAX_VALUE).next());
		Expect.assertThrows(ArithmeticException.class, () -> new Epoch(Long.MAX_VALUE).startMillis(60));
	}
}
