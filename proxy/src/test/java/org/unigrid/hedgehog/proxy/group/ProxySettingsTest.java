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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.chain.ChainLimits;
import org.unigrid.hedgehog.proxy.chain.Entry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ProxySettingsTest {
	private static final long[] DEFAULTS = { 8, 3600, 4, 16, 32L * 1024 * 1024, 30, 16 * 1024, 300, 3 };
	private static final String[] NAMES = { "groupSize", "reorganizePeriodSeconds", "chainsPerGroup",
		"clientsPerChain", "chainCapacityBytes", "retentionSeconds", "maxEntryBytes", "sessionIdleSeconds", "flags" };
	private static final long[] BELOW = { 0, 59, 0, 0, 16 * 1024 - 1, 0, 1023, 0, -1 };
	private static final long[] ABOVE = { 0x1_0000, 0x1_0000_0000L, 0x1_0000, 0x1_0000, 0x1_0000_0000L, 0x1_0000,
		1024 * 1024 + 1, 0x1_0000, 4 };

	private static ProxySettings with(int field, long value) {
		final long[] v = DEFAULTS.clone();

		v[field] = value;
		return new ProxySettings((int) v[0], v[1], (int) v[2], (int) v[3], v[4], (int) v[5], v[6], (int) v[7],
			(int) v[8]);
	}

	@Provide
	public Arbitrary<ProxySettings> settings() {
		final Arbitrary<Integer> shorts = Arbitraries.integers().between(1, 0xFFFF);

		return Combinators.combine(shorts, Arbitraries.longs().between(60, 0xFFFF_FFFFL), shorts, shorts,
			Arbitraries.longs().between(1024, 1024 * 1024), shorts, Arbitraries.integers().between(0, 3))
			.as((size, period, chains, clients, maxEntry, idle, flags) -> new ProxySettings(size, period, chains,
				clients, maxEntry * 4, 30, maxEntry, idle, flags));
	}

	@Example
	public void shouldDefaultToTheSporkTable() {
		final ProxySettings defaults = ProxySettings.DEFAULTS;

		assertThat(defaults, equalTo(with(0, 8)));
		assertThat(defaults.chainCapacityBytes(), is(33_554_432L));
		assertThat(defaults.maxEntryBytes(), is(16_384L));
		assertThat(defaults.connectAllowed(), is(true));
		assertThat(defaults.udpAllowed(), is(true));
		assertThat(defaults.sessionIdleMillis(), is(300_000L));
		assertThat(defaults.chainLimits(), equalTo(new ChainLimits(33_554_432L, 30, 16_384)));
	}

	@Example
	public void shouldAcceptEveryBoundItself() {
		final long[] lowest = { 1, 60, 1, 1, 16 * 1024, 1, 1024, 1, 0 };
		final long[] highest = { 0xFFFF, 0xFFFF_FFFFL, 0xFFFF, 0xFFFF, 0xFFFF_FFFFL, 0xFFFF, 1024 * 1024, 0xFFFF, 3 };

		for (int field = 0; field < DEFAULTS.length; field++) {
			assertThat(with(field, lowest[field]).toString(), containsString(NAMES[field] + "=" + lowest[field]));
			assertThat(with(field, highest[field]).toString(), containsString(NAMES[field] + "=" + highest[field]));
		}
	}

	@Property(tries = 9)
	public void shouldRefuseEveryFieldOutsideItsBounds(@ForAll @IntRange(min = 0, max = 8) int field) {
		final IllegalArgumentException below = Expect.assertThrows(IllegalArgumentException.class,
			() -> with(field, BELOW[field]));
		final IllegalArgumentException above = Expect.assertThrows(IllegalArgumentException.class,
			() -> with(field, ABOVE[field]));

		assertThat(below.getMessage(), containsString(NAMES[field]));
		assertThat(above.getMessage(), containsString(NAMES[field]));
	}

	@Example
	public void shouldRefuseACapacityBelowTheEntrySize() {
		Expect.assertThrows(IllegalArgumentException.class, () -> new ProxySettings(8, 3600, 4, 16, 64 * 1024, 30,
			64 * 1024 + 1, 300, 3));
	}

	@Property(tries = 100)
	public void shouldDeriveChainLimitsThatHold(@ForAll("settings") ProxySettings settings) {
		final ChainLimits limits = settings.chainLimits();

		assertThat(limits.capacityBytes(), is(settings.chainCapacityBytes()));
		assertThat(limits.retentionSeconds(), is(settings.retentionSeconds()));
		assertThat(limits.maxPayloadBytes(), is((int) settings.maxEntryBytes() - Entry.OVERHEAD));
	}

	@Property(tries = 100)
	public void shouldReadEachCommandFromItsOwnBit(@ForAll("settings") ProxySettings settings) {
		assertThat(settings.connectAllowed(), is((settings.flags() & 1) != 0));
		assertThat(settings.udpAllowed(), is((settings.flags() & 2) != 0));
	}

	@Example
	public void shouldTakeTheEpochFromItsPeriod() {
		assertThat(ProxySettings.DEFAULTS.epochAt(7_200_000), equalTo(new Epoch(2)));
	}

	@Example
	public void shouldDeriveEveryValueFromItsOwnField() {
		final ProxySettings settings = new ProxySettings(5, 7200, 6, 7, 65_536, 77, 2048, 99, ProxySettings.CONNECT);

		assertThat(settings.chainLimits(), equalTo(new ChainLimits(65_536, 77, 2048)));
		assertThat(settings.epochAt(14_400_000), equalTo(new Epoch(2)));
		assertThat(settings.epochAt(14_399_999), equalTo(new Epoch(1)));
		assertThat(settings.sessionIdleMillis(), is(99_000L));
		assertThat(settings.connectAllowed(), is(true));
		assertThat(settings.udpAllowed(), is(false));
	}

	@Example
	public void shouldDeriveTheWireMaximaWithoutOverflow() {
		final ProxySettings widest = new ProxySettings(0xFFFF, 0xFFFF_FFFFL, 0xFFFF, 0xFFFF, 0xFFFF_FFFFL, 0xFFFF,
			1024 * 1024, 0xFFFF, ProxySettings.UDP);

		assertThat(widest.chainLimits(), equalTo(new ChainLimits(0xFFFF_FFFFL, 0xFFFF, 1024 * 1024)));
		assertThat(widest.chainLimits().maxPayloadBytes(), is(1024 * 1024 - Entry.OVERHEAD));
		assertThat(widest.epochAt(0xFFFF_FFFFL * 1000), equalTo(new Epoch(1)));
		assertThat(widest.epochAt(0xFFFF_FFFFL * 1000 - 1), equalTo(new Epoch(0)));
		assertThat(widest.sessionIdleMillis(), is(65_535_000L));
		assertThat(widest.connectAllowed(), is(false));
		assertThat(widest.udpAllowed(), is(true));
	}

	@Example
	public void shouldAllowEachCommandAlone() {
		assertThat(with(8, 0).connectAllowed(), is(false));
		assertThat(with(8, 0).udpAllowed(), is(false));
		assertThat(with(8, ProxySettings.CONNECT).connectAllowed(), is(true));
		assertThat(with(8, ProxySettings.CONNECT).udpAllowed(), is(false));
		assertThat(with(8, ProxySettings.UDP).connectAllowed(), is(false));
		assertThat(with(8, ProxySettings.UDP).udpAllowed(), is(true));
	}

	@Property(tries = 30)
	public void shouldRefuseEveryUnknownFlagBit(@ForAll @IntRange(min = 2, max = 31) int bit) {
		final IllegalArgumentException alone = Expect.assertThrows(IllegalArgumentException.class,
			() -> with(8, 1L << bit));
		final IllegalArgumentException known = Expect.assertThrows(IllegalArgumentException.class,
			() -> with(8, 1L << bit | ProxySettings.CONNECT | ProxySettings.UDP));

		assertThat(alone.getMessage(), containsString("flags"));
		assertThat(known.getMessage(), containsString("flags"));
	}

	@Example
	public void shouldNameTheEntrySizeWhenItAndTheCapacityAreBothOutOfBounds() {
		final IllegalArgumentException tooLarge = Expect.assertThrows(IllegalArgumentException.class,
			() -> new ProxySettings(8, 3600, 4, 16, 1024, 30, 1024 * 1024 + 1, 300, 3));
		final IllegalArgumentException tooSmall = Expect.assertThrows(IllegalArgumentException.class,
			() -> new ProxySettings(8, 3600, 4, 16, 0, 30, 1023, 300, 3));

		assertThat(tooLarge.getMessage(), containsString("maxEntryBytes"));
		assertThat(tooSmall.getMessage(), containsString("maxEntryBytes"));
	}

	@Example
	public void shouldNameTheCapacityWhenItIsOneBelowTheEntrySize() {
		final IllegalArgumentException thrown = Expect.assertThrows(IllegalArgumentException.class,
			() -> new ProxySettings(8, 3600, 4, 16, 1024 * 1024 - 1, 30, 1024 * 1024, 300, 3));

		assertThat(thrown.getMessage(), containsString("chainCapacityBytes"));
	}
}
