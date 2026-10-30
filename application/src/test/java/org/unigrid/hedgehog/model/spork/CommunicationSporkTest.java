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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Builders;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Shrinkable;
import net.jqwik.api.constraints.LongRange;
import org.unigrid.hedgehog.model.spork.CommunicationSpork.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class CommunicationSporkTest {
	public static final int MAX_UNSIGNED_SHORT = 0xFFFF;
	public static final long MAX_UNSIGNED_INT = 0xFFFF_FFFFL;
	private static final int DRAWS = 500;
	private static final long DRAW_SEED = 42;

	@AllArgsConstructor
	enum ShortCount {
		GROUP_SIZE(SporkData::setGroupSize), CHAINS_PER_GROUP(SporkData::setChainsPerGroup),
		CLIENTS_PER_CHAIN(SporkData::setClientsPerChain), RETENTION_SECONDS(SporkData::setRetentionSeconds),
		SESSION_IDLE_SECONDS(SporkData::setSessionIdleSeconds);

		private final ObjIntConsumer<SporkData> setter;
	}

	public static <T> BiFunction<SporkData, T, SporkData> with(BiConsumer<SporkData, T> setter) {
		return (data, value) -> {
			setter.accept(data, value);
			return data;
		};
	}

	private static Arbitrary<Integer> justBeyond(int minimum, int maximum) {
		return Arbitraries.oneOf(Arbitraries.of(minimum - 1, minimum, maximum, maximum + 1),
			Arbitraries.integers().between(minimum - 1, maximum + 1)
		);
	}

	private static Arbitrary<Long> justBeyond(long minimum, long maximum) {
		return Arbitraries.oneOf(Arbitraries.of(minimum - 1, minimum, maximum, maximum + 1),
			Arbitraries.longs().between(minimum - 1, maximum + 1)
		);
	}

	public static Arbitrary<SporkData> sporkDataAcrossTheBounds() {
		return Builders.withBuilder(SporkData::new)
			.use(justBeyond(1, MAX_UNSIGNED_SHORT)).in(with(SporkData::setGroupSize))
			.use(justBeyond(60L, MAX_UNSIGNED_INT)).in(with(SporkData::setReorganizePeriodSeconds))
			.use(justBeyond(1, MAX_UNSIGNED_SHORT)).in(with(SporkData::setChainsPerGroup))
			.use(justBeyond(1, MAX_UNSIGNED_SHORT)).in(with(SporkData::setClientsPerChain))
			.use(justBeyond(0L, MAX_UNSIGNED_INT)).in(with(SporkData::setChainCapacityBytes))
			.use(justBeyond(1, MAX_UNSIGNED_SHORT)).in(with(SporkData::setRetentionSeconds))
			.use(justBeyond(1024L, 1_048_576L)).in(with(SporkData::setMaxEntryBytes))
			.use(justBeyond(1, MAX_UNSIGNED_SHORT)).in(with(SporkData::setSessionIdleSeconds))
			.use(justBeyond(0, 0x03)).in(with(SporkData::setCommunicationFlags))
			.build();
	}

	private static <T> Set<T> drawnFrom(Function<SporkData, T> field) {
		return sporkDataAcrossTheBounds().generator(DRAWS).stream(new Random(DRAW_SEED)).limit(DRAWS)
			.map(Shrinkable::value).map(field).collect(Collectors.toSet());
	}

	public static boolean accepts(SporkData data) {
		try {
			data.validate();
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static SporkData changed(Consumer<SporkData> change) {
		final SporkData data = new SporkData();

		change.accept(data);
		return data;
	}

	private static boolean acceptsAfter(Consumer<SporkData> change) {
		return accepts(changed(change));
	}

	private static String refusalOf(Consumer<SporkData> change) {
		final SporkData data = changed(change);

		return assertThrows(IllegalArgumentException.class, data::validate).getMessage();
	}

	private static boolean acceptsShortCount(ShortCount count, int value) {
		return acceptsAfter(data -> count.setter.accept(data, value));
	}

	private static boolean acceptsFlags(int flags) {
		return acceptsAfter(data -> data.setCommunicationFlags(flags));
	}

	private static boolean acceptsCapacity(long maxEntryBytes, long capacity) {
		return acceptsAfter(data -> {
			data.setMaxEntryBytes(maxEntryBytes);
			data.setChainCapacityBytes(capacity);
		});
	}

	private static void assertCapacityBounds(long maxEntryBytes) {
		assertThat(acceptsCapacity(maxEntryBytes, 4 * maxEntryBytes - 1), is(false));
		assertThat(acceptsCapacity(maxEntryBytes, 4 * maxEntryBytes), is(true));
		assertThat(acceptsCapacity(maxEntryBytes, MAX_UNSIGNED_INT), is(true));
		assertThat(acceptsCapacity(maxEntryBytes, MAX_UNSIGNED_INT + 1), is(false));
	}

	private static Arbitrary<Integer> intsAround(int lower, int upper) {
		return Arbitraries.oneOf(Arbitraries.integers().between(lower - 2, lower + 2),
			Arbitraries.integers().between(upper - 2, upper + 2), Arbitraries.integers()
		);
	}

	private static Arbitrary<Long> longsAround(long lower, long upper) {
		return Arbitraries.oneOf(Arbitraries.longs().between(lower - 2, lower + 2),
			Arbitraries.longs().between(upper - 2, upper + 2), Arbitraries.longs()
		);
	}

	@Provide
	public Arbitrary<Integer> aroundTheShortCountBounds() {
		return intsAround(1, MAX_UNSIGNED_SHORT);
	}

	@Provide
	public Arbitrary<Integer> aroundTheKnownFlags() {
		return intsAround(0, 0x03);
	}

	@Provide
	public Arbitrary<Long> aroundTheReorganizePeriodBounds() {
		return longsAround(60, MAX_UNSIGNED_INT);
	}

	@Provide
	public Arbitrary<Long> aroundTheEntrySizeBounds() {
		return longsAround(1024, 1_048_576);
	}

	@Provide
	public Arbitrary<Long> aroundTheChainCapacityBounds() {
		return longsAround(4 * new SporkData().getMaxEntryBytes(), MAX_UNSIGNED_INT);
	}

	@Example
	public void hasValidProxyDefaults() {
		final SporkData data = new CommunicationSpork().getData();

		data.validate();
		assertThat(data.getGroupSize(), equalTo(8));
		assertThat(data.getReorganizePeriodSeconds(), equalTo(3600L));
		assertThat(data.getChainsPerGroup(), equalTo(4));
		assertThat(data.getClientsPerChain(), equalTo(16));
		assertThat(data.getChainCapacityBytes(), equalTo(33_554_432L));
		assertThat(data.getRetentionSeconds(), equalTo(30));
		assertThat(data.getMaxEntryBytes(), equalTo(16_384L));
		assertThat(data.getSessionIdleSeconds(), equalTo(300));
		assertThat(data.getCommunicationFlags(), equalTo(0x03));
		assertThat(data.allows(CommunicationFlag.CONNECT), is(true));
		assertThat(data.allows(CommunicationFlag.UDP), is(true));
	}

	@Example
	public void isRegisteredAsASporkType() {
		final SporkDatabase sporkDatabase = SporkDatabase.builder().build();
		final GridSpork spork = GridSpork.create(GridSpork.Type.COMMUNICATION);

		assertThat(GridSpork.Type.get((short) 1060), equalTo(GridSpork.Type.COMMUNICATION));
		assertThat(spork.getType(), equalTo(GridSpork.Type.COMMUNICATION));

		sporkDatabase.set(spork);
		assertThat(sporkDatabase.getCommunicationSpork(), sameInstance(spork));
	}

	@Example
	public void emptyIsAFreshInstanceOfTheDefaults() {
		final SporkData data = new CommunicationSpork().getData();
		data.setGroupSize(99);
		data.setSessionIdleSeconds(1);

		final SporkData empty = data.empty();

		assertThat(empty, equalTo(new SporkData()));
		assertThat(empty, not(sameInstance(data)));
		assertThat(empty, not(sameInstance(data.empty())));
	}

	@Example
	public void pinsTheSerialVersionOfItsSignedData() throws ReflectiveOperationException {
		final Field version = SporkData.class.getDeclaredField("serialVersionUID");
		version.setAccessible(true);

		assertThat(Modifier.isStatic(version.getModifiers()), is(true));
		assertThat(Modifier.isFinal(version.getModifiers()), is(true));
		assertThat(version.getType(), equalTo(long.class));
		assertThat(version.getLong(null), equalTo(1L));
	}

	@Example
	public void keepsShortCountsBetweenOneAndTheWireWidth() {
		for (ShortCount count : ShortCount.values()) {
			assertThat(count + " at 0", acceptsShortCount(count, 0), is(false));
			assertThat(count + " at 1", acceptsShortCount(count, 1), is(true));
			assertThat(count + " at 65535", acceptsShortCount(count, MAX_UNSIGNED_SHORT), is(true));
			assertThat(count + " at 65536", acceptsShortCount(count, MAX_UNSIGNED_SHORT + 1), is(false));
		}
	}

	@Example
	public void keepsReorganizePeriodsBetweenAMinuteAndTheWireWidth() {
		assertThat(acceptsAfter(data -> data.setReorganizePeriodSeconds(59)), is(false));
		assertThat(acceptsAfter(data -> data.setReorganizePeriodSeconds(60)), is(true));
		assertThat(acceptsAfter(data -> data.setReorganizePeriodSeconds(MAX_UNSIGNED_INT)), is(true));
		assertThat(acceptsAfter(data -> data.setReorganizePeriodSeconds(MAX_UNSIGNED_INT + 1)), is(false));
	}

	@Example
	public void keepsEntrySizesBetweenAKibibyteAndAMebibyte() {
		assertThat(acceptsAfter(data -> data.setMaxEntryBytes(1023)), is(false));
		assertThat(acceptsAfter(data -> data.setMaxEntryBytes(1024)), is(true));
		assertThat(acceptsAfter(data -> data.setMaxEntryBytes(1_048_576)), is(true));
		assertThat(acceptsAfter(data -> data.setMaxEntryBytes(1_048_577)), is(false));
	}

	@Example
	public void keepsChainCapacitiesFromFourEntriesToTheWireWidthAtTheSmallestEntrySize() {
		assertCapacityBounds(1024);
	}

	@Example
	public void keepsChainCapacitiesFromFourEntriesToTheWireWidthAtTheLargestEntrySize() {
		assertCapacityBounds(1_048_576);
	}

	@Example
	public void namesTheShortCountThatIsOutOfRange() {
		assertThat(refusalOf(data -> data.setGroupSize(0)), containsString("groupSize"));
		assertThat(refusalOf(data -> data.setChainsPerGroup(0)), containsString("chainsPerGroup"));
		assertThat(refusalOf(data -> data.setClientsPerChain(0)), containsString("clientsPerChain"));
		assertThat(refusalOf(data -> data.setRetentionSeconds(0)), containsString("retentionSeconds"));
		assertThat(refusalOf(data -> data.setSessionIdleSeconds(0)), containsString("sessionIdleSeconds"));
	}

	@Example
	public void namesTheWiderFieldThatIsOutOfRange() {
		assertThat(refusalOf(data -> data.setReorganizePeriodSeconds(59)),
			containsString("reorganizePeriodSeconds"));
		assertThat(refusalOf(data -> data.setMaxEntryBytes(1023)), containsString("maxEntryBytes"));
		assertThat(refusalOf(data -> data.setChainCapacityBytes(0)), containsString("chainCapacityBytes"));
	}

	@Example
	public void namesTheEntrySizeBeforeTheChainCapacity() {
		final String refusal = refusalOf(data -> {
			data.setMaxEntryBytes(1023);
			data.setChainCapacityBytes(0);
		});

		assertThat(refusal, containsString("maxEntryBytes"));
	}

	@Example
	public void statesTheFieldTheRangeAndTheValueThatWasRefused() {
		assertThat(refusalOf(data -> data.setGroupSize(0)), equalTo("groupSize must be 1-65535, not 0"));
		assertThat(refusalOf(data -> data.setReorganizePeriodSeconds(MAX_UNSIGNED_INT + 1)),
			equalTo("reorganizePeriodSeconds must be 60-4294967295, not 4294967296"));
		assertThat(refusalOf(data -> data.setMaxEntryBytes(1_048_577)),
			equalTo("maxEntryBytes must be 1024-1048576, not 1048577"));
		assertThat(refusalOf(data -> data.setChainCapacityBytes(-1)),
			equalTo("chainCapacityBytes must hold four of the largest entries and be at most 4294967295, not -1"));
	}

	@Example
	public void acceptsEveryCombinationOfTheKnownFlags() {
		for (int flags : new int[] {0, 1, 2, 3}) {
			assertThat("flags " + flags, acceptsFlags(flags), is(true));
		}
	}

	@Example
	public void refusesEveryValueHoldingAnUnknownBit() {
		final int[] unknown = {-1, 4, 0x07, 0x8000, 0xFFFF, 0x10000, 0x10001, 0x10003,
			Integer.MIN_VALUE, Integer.MAX_VALUE};

		for (int flags : unknown) {
			assertThat("flags " + flags, acceptsFlags(flags), is(false));
		}
	}

	@Example
	public void statesTheUnknownFlagsThatWereRefused() {
		assertThat(refusalOf(data -> data.setCommunicationFlags(0x10003)),
			equalTo("communicationFlags holds unknown flags: 0x10003"));
		assertThat(refusalOf(data -> data.setCommunicationFlags(-1)),
			equalTo("communicationFlags holds unknown flags: 0xffffffff"));
	}

	@Example
	public void drawsTheExactBoundsAndTheValuesJustBeyondThem() {
		assertThat(drawnFrom(SporkData::getCommunicationFlags), hasItems(-1, 0, 1, 2, 3, 4));
		assertThat(drawnFrom(SporkData::getGroupSize), hasItems(0, 1, MAX_UNSIGNED_SHORT, MAX_UNSIGNED_SHORT + 1));
		assertThat(drawnFrom(SporkData::getReorganizePeriodSeconds),
			hasItems(59L, 60L, MAX_UNSIGNED_INT, MAX_UNSIGNED_INT + 1));
	}

	@Property
	public void acceptsShortCountsExactlyInRange(@ForAll ShortCount count,
		@ForAll("aroundTheShortCountBounds") int value) {

		final SporkData data = new SporkData();

		count.setter.accept(data, value);
		assertThat(accepts(data), is(value >= 1 && value <= MAX_UNSIGNED_SHORT));
	}

	@Property
	public void acceptsReorganizePeriodsExactlyInRange(@ForAll("aroundTheReorganizePeriodBounds") long seconds) {
		final SporkData data = new SporkData();

		data.setReorganizePeriodSeconds(seconds);
		assertThat(accepts(data), is(seconds >= 60 && seconds <= MAX_UNSIGNED_INT));
	}

	@Property
	public void acceptsEntrySizesExactlyInRange(@ForAll("aroundTheEntrySizeBounds") long maxEntryBytes) {
		final SporkData data = new SporkData();

		data.setMaxEntryBytes(maxEntryBytes);
		assertThat(accepts(data), is(maxEntryBytes >= 1024 && maxEntryBytes <= 1_048_576));
	}

	@Property
	public void acceptsChainCapacitiesExactlyInRange(@ForAll("aroundTheChainCapacityBounds") long capacity) {
		final SporkData data = new SporkData();

		data.setChainCapacityBytes(capacity);
		assertThat(accepts(data), is(capacity >= 4 * data.getMaxEntryBytes() && capacity <= MAX_UNSIGNED_INT));
	}

	/* An exit keeps room for what it relays, at least one of the largest entries, beside the entries of its own, so
	   a chain of fewer than four of them can leave it no room for one of its own */
	@Property
	public void refusesChainsThatCannotHoldFourOfTheLargestEntries(
		@ForAll @LongRange(min = 1024, max = 1_048_576) long maxEntryBytes,
		@ForAll @LongRange(min = -2, max = 2) long slack) {

		final SporkData data = new SporkData();

		data.setMaxEntryBytes(maxEntryBytes);
		data.setChainCapacityBytes(4 * maxEntryBytes + slack);
		assertThat(accepts(data), is(slack >= 0));
	}

	@Property
	public void acceptsOnlyKnownFlags(@ForAll("aroundTheKnownFlags") int flags) {
		final SporkData data = new SporkData();

		data.setCommunicationFlags(flags);
		assertThat(accepts(data), is(flags >= 0 && flags <= 0x03));
	}

	@Property
	public void allowsExactlyTheFlagsItHolds(@ForAll Set<CommunicationFlag> flags) {
		final SporkData data = new SporkData();

		data.setCommunicationFlags(CommunicationFlag.of(flags.toArray(CommunicationFlag[]::new)));

		for (CommunicationFlag flag : CommunicationFlag.values()) {
			assertThat(data.allows(flag), is(flags.contains(flag)));
		}
	}
}
