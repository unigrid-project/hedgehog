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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;

public class ShardGroupsTest {
	private static final int LARGEST_GROUP_SIZE = 65_535;

	@Provide
	public Arbitrary<Set<String>> gridnodeIds() {
		return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12).set().ofMaxSize(60);
	}

	private static List<List<String>> all(ShardGroups groups) {
		return IntStream.range(0, groups.groupCount()).mapToObj(groups::members).toList();
	}

	private static List<String> gridnodes(int count) {
		return IntStream.range(0, count).mapToObj(i -> "gn-" + i).toList();
	}

	private static List<List<String>> partition(long epoch, int groupSize, List<String> ids) {
		return all(ShardGroups.of(new Epoch(epoch), groupSize, ids));
	}

	@Property(tries = 100)
	public void shouldNotDependOnTheOrderOfTheIds(@ForAll("gridnodeIds") Set<String> ids, @ForAll Random random,
		@ForAll @LongRange(min = 0, max = 1L << 40) long epoch, @ForAll @IntRange(min = 1, max = 10) int groupSize) {
		final List<String> shuffled = new ArrayList<>(ids);

		Collections.shuffle(shuffled, random);
		shuffled.addAll(shuffled.subList(0, shuffled.size() / 2));
		assertThat(all(ShardGroups.of(new Epoch(epoch), groupSize, shuffled)),
			equalTo(all(ShardGroups.of(new Epoch(epoch), groupSize, ids))));
	}

	@Property(tries = 200)
	public void shouldPlaceEveryNodeInExactlyOneOfBalancedGroups(@ForAll("gridnodeIds") Set<String> ids,
		@ForAll @LongRange(min = 0, max = 1L << 40) long epoch, @ForAll @IntRange(min = 1, max = 10) int groupSize) {
		final ShardGroups groups = ShardGroups.of(new Epoch(epoch), groupSize, ids);
		final List<String> placed = all(groups).stream().flatMap(List::stream).toList();
		final IntSummaryStatistics sizes = all(groups).stream().mapToInt(List::size).summaryStatistics();

		assertThat(groups.groupCount(), is(ids.isEmpty() ? 0 : Math.max(1, ids.size() / groupSize)));
		assertThat(placed.size(), is(ids.size()));
		assertThat(new HashSet<>(placed), equalTo(ids));
		assertThat(sizes.getMax() - sizes.getMin(), lessThanOrEqualTo(1));

		for (int group = 0; group < groups.groupCount(); group++) {
			for (String id : groups.members(group)) {
				assertThat(groups.groupOf(id), equalTo(OptionalInt.of(group)));
			}
		}
	}

	@Property(tries = 100)
	public void shouldKeepEveryNodeTogetherWhenTheGroupSizeIsAtLeastTheNodeCount(
		@ForAll("gridnodeIds") Set<String> ids, @ForAll @LongRange(min = 0, max = 1L << 40) long epoch,
		@ForAll @IntRange(min = 60, max = LARGEST_GROUP_SIZE) int groupSize) {
		final ShardGroups groups = ShardGroups.of(new Epoch(epoch), groupSize, ids);

		assertThat(groups.groupCount(), is(ids.isEmpty() ? 0 : 1));
		assertThat(all(groups).stream().flatMap(List::stream).collect(Collectors.toSet()), equalTo(ids));
	}

	@Example
	public void shouldHaveNoGroupsWithoutGridnodes() {
		final ShardGroups groups = ShardGroups.of(new Epoch(1), 8, List.of());

		assertThat(groups.groupCount(), is(0));
		assertThat(groups.groupOf("alpha"), equalTo(OptionalInt.empty()));
	}

	@Example
	public void shouldHaveNoGroupsWithoutGridnodesAtTheLargestGroupSize() {
		final ShardGroups groups = ShardGroups.of(new Epoch(0), LARGEST_GROUP_SIZE, List.of());

		assertThat(groups.groupCount(), is(0));
		assertThat(all(groups), equalTo(List.of()));
	}

	@Example
	public void shouldKeepFewerNodesThanTheGroupSizeInOneGroup() {
		final ShardGroups groups = ShardGroups.of(new Epoch(1), 8, List.of("alpha", "bravo", "charlie"));

		assertThat(groups.groupCount(), is(1));
		assertThat(new HashSet<>(groups.members(0)), equalTo(Set.of("alpha", "bravo", "charlie")));
	}

	/* Expected values computed independently with Python's hashlib over the same byte layout */
	@Example
	public void shouldMatchTheKnownPartitions() {
		final ShardGroups first = ShardGroups.of(new Epoch(1), 2, List.of("alpha", "bravo", "charlie", "delta",
			"echo"));
		final ShardGroups second = ShardGroups.of(new Epoch(486_111), 3, IntStream.range(0, 7)
			.mapToObj(i -> "gn-" + i).toList());

		assertThat(all(first), equalTo(List.of(List.of("delta", "bravo", "charlie"), List.of("echo", "alpha"))));
		assertThat(all(second), equalTo(List.of(List.of("gn-5", "gn-3", "gn-6", "gn-0"),
			List.of("gn-4", "gn-2", "gn-1"))));
	}

	@Example
	public void shouldReshuffleInTheNextEpoch() {
		final List<String> ids = IntStream.range(0, 64).mapToObj(i -> "gn-" + i).toList();

		assertThat(all(ShardGroups.of(new Epoch(2), 8, ids)), not(equalTo(all(ShardGroups.of(new Epoch(1), 8, ids)))));
	}

	@Example
	public void shouldRefuseAnEmptyGroupSize() {
		Expect.assertThrows(IllegalArgumentException.class, () -> ShardGroups.of(new Epoch(1), 0, List.of("alpha")));
	}

	@Example
	public void shouldRefuseANegativeGroupSize() {
		Expect.assertThrows(IllegalArgumentException.class, () -> ShardGroups.of(new Epoch(1), -1, List.of("alpha")));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> ShardGroups.of(new Epoch(1), Integer.MIN_VALUE, List.of("alpha")));
	}

	@Example
	public void shouldRefuseTheGroupSizeBeforeLookingAtTheIds() {
		Expect.assertThrows(IllegalArgumentException.class, () -> ShardGroups.of(new Epoch(1), 0, null));
	}

	@Example
	public void shouldCountEveryDuplicatedIdOnce() {
		final List<String> repeated = List.of("alpha", "alpha", "bravo", "charlie", "delta", "echo", "echo", "alpha");

		assertThat(partition(1, 2, repeated), equalTo(List.of(List.of("delta", "bravo", "charlie"),
			List.of("echo", "alpha"))));
	}

	@Example
	public void shouldNotDoubleTheGroupCountThroughDuplicatedIds() {
		final List<String> doubled = new ArrayList<>(gridnodes(5));

		doubled.addAll(gridnodes(5));
		assertThat(partition(7, 3, doubled), equalTo(partition(7, 3, gridnodes(5))));
		assertThat(partition(7, 3, doubled).size(), is(1));
	}

	@Example
	public void shouldFormAnotherGroupOnlyAtAWholeGroupSize() {
		assertThat(partition(7, 3, gridnodes(3)), equalTo(List.of(List.of("gn-2", "gn-0", "gn-1"))));
		assertThat(partition(7, 3, gridnodes(5)), equalTo(List.of(List.of("gn-4", "gn-2", "gn-0", "gn-1", "gn-3"))));
		assertThat(partition(7, 3, gridnodes(6)), equalTo(List.of(List.of("gn-4", "gn-0", "gn-1"),
			List.of("gn-2", "gn-5", "gn-3"))));
	}

	@Example
	public void shouldMakeOneGroupPerNodeAtGroupSizeOne() {
		assertThat(partition(7, 1, gridnodes(7)), equalTo(List.of(List.of("gn-4"), List.of("gn-2"), List.of("gn-0"),
			List.of("gn-5"), List.of("gn-6"), List.of("gn-1"), List.of("gn-3"))));
	}

	@Example
	public void shouldPlaceASingleNodeInGroupZero() {
		for (int groupSize : new int[] { 1, 2, LARGEST_GROUP_SIZE, Integer.MAX_VALUE }) {
			final ShardGroups groups = ShardGroups.of(new Epoch(7), groupSize, List.of("solo"));

			assertThat(all(groups), equalTo(List.of(List.of("solo"))));
			assertThat(groups.groupOf("solo"), equalTo(OptionalInt.of(0)));
		}
	}

	@Example
	public void shouldKeepAllNodesInRankOrderInOneGroupAtTheLargestGroupSizes() {
		final List<String> ids = List.of("alpha", "bravo", "charlie", "delta", "echo");
		final List<List<String>> everyone = List.of(List.of("alpha", "delta", "echo", "charlie", "bravo"));

		assertThat(partition(0, LARGEST_GROUP_SIZE, ids), equalTo(everyone));
		assertThat(partition(0, Integer.MAX_VALUE, ids), equalTo(everyone));
	}

	@Example
	public void shouldFillWholeGroupsAtTheLargestGroupSize() {
		final ShardGroups exactlyOne = ShardGroups.of(new Epoch(0), LARGEST_GROUP_SIZE, gridnodes(65_535));
		final ShardGroups almostTwo = ShardGroups.of(new Epoch(0), LARGEST_GROUP_SIZE, gridnodes(131_069));
		final ShardGroups exactlyTwo = ShardGroups.of(new Epoch(0), LARGEST_GROUP_SIZE, gridnodes(131_070));

		assertThat(exactlyOne.groupCount(), is(1));
		assertThat(exactlyOne.members(0).size(), is(65_535));
		assertThat(exactlyOne.members(0).get(0), is("gn-2815"));
		assertThat(exactlyOne.members(0).get(65_534), is("gn-15574"));
		assertThat(almostTwo.groupCount(), is(1));
		assertThat(almostTwo.members(0).size(), is(131_069));
		assertThat(almostTwo.members(0).get(0), is("gn-71043"));
		assertThat(almostTwo.members(0).get(131_068), is("gn-15574"));
		assertThat(exactlyTwo.groupCount(), is(2));
		assertThat(exactlyTwo.members(0).size(), is(65_535));
		assertThat(exactlyTwo.members(0).get(0), is("gn-71043"));
		assertThat(exactlyTwo.members(0).get(65_534), is("gn-48220"));
		assertThat(exactlyTwo.members(1).size(), is(65_535));
		assertThat(exactlyTwo.members(1).get(0), is("gn-82290"));
		assertThat(exactlyTwo.members(1).get(65_534), is("gn-15574"));
	}

	@Example
	public void shouldUseAllEightBytesOfTheEpoch() {
		assertThat(partition(Long.MAX_VALUE, 2, List.of("alpha", "bravo", "charlie", "delta", "echo")),
			equalTo(List.of(List.of("echo", "alpha", "delta"), List.of("bravo", "charlie"))));
		assertThat(partition((1L << 40) + 5, 3, gridnodes(7)),
			equalTo(List.of(List.of("gn-4", "gn-5", "gn-0", "gn-3"), List.of("gn-2", "gn-1", "gn-6"))));
	}

	@Example
	public void shouldRankIdsByTheirUtf8Bytes() {
		assertThat(partition(3, 2, List.of("ö", "é", "日本", "a")),
			equalTo(List.of(List.of("日本", "ö"), List.of("a", "é"))));
	}

	/* A lone surrogate encodes to the same UTF-8 bytes as "?", the only way for two ids to score alike */
	@Example
	public void shouldRankIdsWithEqualScoresByTheirText() {
		final List<List<String>> expected = List.of(List.of("?"), List.of("\uD800"));

		assertThat(partition(1, 1, List.of("?", "\uD800")), equalTo(expected));
		assertThat(partition(1, 1, List.of("\uD800", "?")), equalTo(expected));
	}

	@Example
	public void shouldFindTheGroupOfEveryKnownNodeAndNoneOfAStranger() {
		final ShardGroups groups = ShardGroups.of(new Epoch(1), 2, List.of("alpha", "bravo", "charlie", "delta",
			"echo"));

		assertThat(groups.groupOf("delta"), equalTo(OptionalInt.of(0)));
		assertThat(groups.groupOf("bravo"), equalTo(OptionalInt.of(0)));
		assertThat(groups.groupOf("charlie"), equalTo(OptionalInt.of(0)));
		assertThat(groups.groupOf("echo"), equalTo(OptionalInt.of(1)));
		assertThat(groups.groupOf("alpha"), equalTo(OptionalInt.of(1)));
		assertThat(groups.groupOf("foxtrot"), equalTo(OptionalInt.empty()));
	}

	@Example
	public void shouldRefuseAGroupThatDoesNotExist() {
		final ShardGroups groups = ShardGroups.of(new Epoch(1), 2, List.of("alpha", "bravo", "charlie", "delta",
			"echo"));

		Expect.assertThrows(IndexOutOfBoundsException.class, () -> groups.members(-1));
		Expect.assertThrows(IndexOutOfBoundsException.class, () -> groups.members(2));
		Expect.assertThrows(IndexOutOfBoundsException.class,
			() -> ShardGroups.of(new Epoch(1), 2, List.of()).members(0));
	}

	@Example
	public void shouldNotLetACallerChangeTheMembers() {
		final ShardGroups groups = ShardGroups.of(new Epoch(1), 2, List.of("alpha", "bravo", "charlie"));

		Expect.assertThrows(UnsupportedOperationException.class, () -> groups.members(0).add("delta"));
		assertThat(groups.members(0).size(), is(3));
	}
}
