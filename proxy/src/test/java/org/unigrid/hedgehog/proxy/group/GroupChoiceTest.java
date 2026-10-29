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

package org.unigrid.hedgehog.proxy.group;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class GroupChoiceTest {
	private static final Epoch EPOCH = new Epoch(486_111);
	private static final Optional<String> SWEDEN = Optional.of("SE");
	private static final Optional<String> NORWAY = Optional.of("NO");
	private static final int DRAWS = 10_000;

	/* How far the share of the draws a group or member got may be from its weight, in absolute terms */
	private static final double TOLERANCE = 0.05;

	/* No country is looked up when none is asked for */
	private static final Function<String, Optional<String>> UNASKED = member -> {
		throw new AssertionError("The country of " + member + " was looked up");
	};

	@Provide
	public Arbitrary<Optional<String>> countries() {
		return Arbitraries.of("SE", "NO", "DK").optional();
	}

	private static ShardGroups groups(final int gridnodes, final int groupSize) {
		return ShardGroups.of(EPOCH, groupSize, IntStream.range(0, gridnodes).mapToObj(i -> "gn-" + i).toList());
	}

	/* The first members of each group, as many as the group is given, are in Sweden and the rest in Norway */
	private static Function<String, Optional<String>> swedes(final ShardGroups groups, final int... perGroup) {
		final Map<String, Optional<String>> countries = new HashMap<>();

		for (int group = 0; group < groups.groupCount(); group++) {
			final List<String> members = groups.members(group);

			for (int member = 0; member < members.size(); member++) {
				countries.put(members.get(member), member < perGroup[group] ? SWEDEN : NORWAY);
			}
		}

		return member -> countries.getOrDefault(member, Optional.empty());
	}

	private static Map<Integer, Long> drawn(final ShardGroups groups, final Optional<String> country,
		final Function<String, Optional<String>> countryOf, final OptionalInt own) {

		final Random random = new Random(1);

		return IntStream.range(0, DRAWS).mapToObj(draw -> GroupChoice.group(groups, country, countryOf, own, random)
			.orElseThrow()).collect(Collectors.groupingBy(group -> group, Collectors.counting()));
	}

	private static double share(final Map<Integer, Long> drawn, final int group) {
		return (double) drawn.getOrDefault(group, 0L) / DRAWS;
	}

	@Property(tries = 200)
	public void neverTheOwnGroup(@ForAll @IntRange(min = 1, max = 60) int gridnodes,
		@ForAll @IntRange(min = 1, max = 8) int groupSize, @ForAll("countries") Optional<String> country,
		@ForAll Random random) {

		final ShardGroups groups = groups(gridnodes, groupSize);
		final Function<String, Optional<String>> countryOf = member -> Optional.of(List.of("SE", "NO", "DK")
			.get(Math.floorMod(member.hashCode(), 3)));

		for (int own = 0; own < groups.groupCount(); own++) {
			final OptionalInt chosen = GroupChoice.group(groups, country, countryOf, OptionalInt.of(own), random);

			assertThat(chosen, not(equalTo(OptionalInt.of(own))));
			assertThat(chosen.isPresent() || groups.groupCount() == 1 || country.isPresent(), is(true));
		}
	}

	@Example
	public void ownGroupAloneHasTheCountryMeansNone() {
		final ShardGroups groups = groups(24, 8);
		final Function<String, Optional<String>> countryOf = swedes(groups, 0, 3, 0);
		final Random random = new Random(1);

		assertThat(groups.groupCount(), is(3));
		assertThat(GroupChoice.group(groups, SWEDEN, countryOf, OptionalInt.empty(), random),
			equalTo(OptionalInt.of(1)));
		assertThat(GroupChoice.group(groups, SWEDEN, countryOf, OptionalInt.of(1), random),
			equalTo(OptionalInt.empty()));
		assertThat(GroupChoice.group(groups, Optional.of("DK"), countryOf, OptionalInt.empty(), random),
			equalTo(OptionalInt.empty()));
	}

	/* Four groups of six with three, two, one and no members in Sweden */
	@Example
	public void weightsFollowMembersInTheCountry() {
		final ShardGroups groups = groups(24, 6);
		final Map<Integer, Long> drawn = drawn(groups, SWEDEN, swedes(groups, 3, 2, 1, 0), OptionalInt.empty());

		assertThat(groups.groupCount(), is(4));
		assertThat(share(drawn, 0), closeTo(3.0 / 6, TOLERANCE));
		assertThat(share(drawn, 1), closeTo(2.0 / 6, TOLERANCE));
		assertThat(share(drawn, 2), closeTo(1.0 / 6, TOLERANCE));
		assertThat(share(drawn, 3), is(0.0));
	}

	/* Five gridnodes in groups of two make a group of three and one of two */
	@Example
	public void noCountryWeightsByGroupSize() {
		final ShardGroups groups = groups(5, 2);
		final Map<Integer, Long> drawn = drawn(groups, Optional.empty(), UNASKED, OptionalInt.empty());

		assertThat(List.of(groups.members(0).size(), groups.members(1).size()), equalTo(List.of(3, 2)));
		assertThat(share(drawn, 0), closeTo(3.0 / 5, TOLERANCE));
		assertThat(share(drawn, 1), closeTo(2.0 / 5, TOLERANCE));
	}

	@Property(tries = 100)
	public void neverAGroupPassedOver(@ForAll @IntRange(min = 0, max = 40) int gridnodes,
		@ForAll("countries") Optional<String> country, @ForAll Set<@IntRange(min = 0, max = 5) Integer> passedOver,
		@ForAll Random random) {

		final ShardGroups groups = groups(gridnodes, 4);
		final OptionalInt chosen = GroupChoice.group(groups, country, member -> SWEDEN, passedOver, random);
		final Set<Integer> open = IntStream.range(0, groups.groupCount()).boxed()
			.filter(group -> !passedOver.contains(group)).collect(Collectors.toSet());

		assertThat(chosen.isPresent(), is(!open.isEmpty() && (country.isEmpty() || country.equals(SWEDEN))));
		chosen.ifPresent(group -> assertThat(open.contains(group), is(true)));
	}

	@Example
	public void exitIsUniformAmongTheMembersInTheCountryNotExcluded() {
		final List<String> members = List.of("gn-0", "gn-1", "gn-2", "gn-3", "gn-4", "gn-5");
		final Set<String> swedes = Set.of("gn-1", "gn-3", "gn-4");
		final Function<String, Optional<String>> countryOf = member -> swedes.contains(member) ? SWEDEN : NORWAY;
		final Random random = new Random(1);
		final Map<String, Long> drawn = IntStream.range(0, DRAWS).mapToObj(draw -> GroupChoice.exit(members, SWEDEN,
			countryOf, Set.of("gn-3"), random).orElseThrow()).collect(Collectors.groupingBy(member -> member,
			Collectors.counting()));

		assertThat(drawn.keySet(), equalTo(Set.of("gn-1", "gn-4")));
		assertThat((double) drawn.get("gn-1") / DRAWS, closeTo(0.5, TOLERANCE));
	}

	@Example
	public void noExitWhenNoMemberLeftIsInTheCountry() {
		final List<String> members = List.of("gn-0", "gn-1", "gn-2");
		final Function<String, Optional<String>> countryOf = member -> member.equals("gn-1") ? SWEDEN : NORWAY;
		final Random random = new Random(1);

		assertThat(GroupChoice.exit(members, SWEDEN, countryOf, Set.of("gn-1"), random), equalTo(Optional.empty()));
		assertThat(GroupChoice.exit(members, Optional.of("DK"), countryOf, Set.of(), random),
			equalTo(Optional.empty()));
		assertThat(GroupChoice.exit(List.of(), Optional.empty(), UNASKED, Set.of(), random),
			equalTo(Optional.empty()));
	}

	@Example
	public void noCountryTakesAnyExitNotExcluded() {
		final List<String> members = List.of("gn-0", "gn-1", "gn-2", "gn-3");
		final Random random = new Random(1);
		final Set<String> drawn = new HashSet<>();

		for (int draw = 0; draw < 200; draw++) {
			drawn.add(GroupChoice.exit(members, Optional.empty(), UNASKED, Set.of("gn-2"), random).orElseThrow());
		}

		assertThat(drawn, equalTo(Set.of("gn-0", "gn-1", "gn-3")));
	}
}
