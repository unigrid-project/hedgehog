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

package org.unigrid.hedgehog.proxy.chain;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ChainLimitsTest {
	@Example
	public void shouldTakeTheCommunicationSporkDefaults() {
		final ChainLimits limits = new ChainLimits(32 * 1024 * 1024, 30, 16 * 1024);

		assertThat(limits.retentionMillis(), is(30_000L));
		assertThat(limits.maxPayloadBytes(), is(16204));
	}

	@Example
	public void shouldKnowItsSmallestClass() {
		assertThat(new ChainLimits(32 * 1024 * 1024, 30, 16 * 1024).smallestClassBytes(), is(1024));
		assertThat(new ChainLimits(1024, 30, Entry.OVERHEAD + 10).smallestClassBytes(), is(Entry.OVERHEAD + 10));
	}

	@Example
	public void shouldReserveASixteenthOfTheCapacityForTheSmallestClass() {
		assertThat(new ChainLimits(32 * 1024 * 1024, 30, 16 * 1024).reserveBytes(), is(2L * 1024 * 1024));
		assertThat(new ChainLimits(16 * 1024 + 15, 30, 16 * 1024).reserveBytes(), is(1024L));
	}

	@Example
	public void shouldReserveAtLeastOneEntryOfTheSmallestClass() {
		assertThat(new ChainLimits(4 * 1024, 30, 4 * 1024).reserveBytes(), is(1024L));
		assertThat(new ChainLimits(4 * 1024, 30, 1024).reserveBytes(), is(1024L));
		assertThat(new ChainLimits(1024, 30, Entry.OVERHEAD + 10).reserveBytes(), is((long) Entry.OVERHEAD + 10));
	}

	@Example
	public void shouldRefuseOneByteBelowTheOverhead() {
		final IllegalArgumentException thrown = Expect.assertThrows(IllegalArgumentException.class,
			() -> new ChainLimits(1024, 30, Entry.OVERHEAD - 1));

		assertThat(thrown.getMessage(), is("An entry needs at least " + Entry.OVERHEAD + " bytes, the limit is "
			+ (Entry.OVERHEAD - 1)));
	}

	@Property(tries = 50)
	public void shouldRefuseAnEntryLimitBelowTheOverhead(@ForAll @IntRange(max = Entry.OVERHEAD - 1) int limit) {
		final IllegalArgumentException thrown = Expect.assertThrows(IllegalArgumentException.class,
			() -> new ChainLimits(1024, 30, limit));

		assertThat(thrown.getMessage(), is("An entry needs at least 180 bytes, the limit is " + limit));
	}

	@Example
	public void shouldRefuseACapacityBelowOneEntry() {
		final IllegalArgumentException thrown = Expect.assertThrows(IllegalArgumentException.class,
			() -> new ChainLimits(1000, 30, 1001));

		assertThat(thrown.getMessage(), is("A chain of 1000 bytes cannot hold an entry of 1001 bytes"));
	}

	@Example
	public void shouldAcceptTheSmallestLimits() {
		final ChainLimits limits = new ChainLimits(Entry.OVERHEAD, 1, Entry.OVERHEAD);

		assertThat(limits.maxPayloadBytes(), is(0));
		assertThat(limits.retentionMillis(), is(1000L));
	}

	@Example
	public void shouldLeaveOneByteOfPayloadOneByteAboveTheOverhead() {
		assertThat(new ChainLimits(1024, 30, Entry.OVERHEAD + 1).maxPayloadBytes(), is(1));
	}

	@Example
	public void shouldTakeTheLargestCapacityTheSporkCanHold() {
		final ChainLimits limits = new ChainLimits(4_294_967_295L, 30, 16 * 1024);

		assertThat(limits.capacityBytes(), is(4_294_967_295L));
	}

	@Example
	public void shouldConvertTheLargestSporkRetentionToMillis() {
		assertThat(new ChainLimits(1024, 65_535, 1024).retentionMillis(), is(65_535_000L));
	}

	@Example
	public void shouldConvertRetentionBeyondAnIntegerOfMillis() {
		assertThat(new ChainLimits(1024, Integer.MAX_VALUE, 1024).retentionMillis(), is(2_147_483_647_000L));
	}

	@Example
	public void shouldRefuseRetentionUnderASecond() {
		final IllegalArgumentException thrown = Expect.assertThrows(IllegalArgumentException.class,
			() -> new ChainLimits(1024, 0, 1024));

		assertThat(thrown.getMessage(), is("Entries are retained for at least a second, found 0"));
	}
}
