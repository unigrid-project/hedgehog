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

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/* The group a client joins and the exit of a session, for the country the exit is to be in. A group is drawn with a
   weight of its members in that country, or of all its members when no country is asked for, so a group with more
   members there is chosen more often, yet any group with one is chosen at times. The countries are those the client
   finds for the members itself: a group never lists the countries it serves, and nothing of the choice tells it
   which country a client asked for. */
public final class GroupChoice {
	private GroupChoice() {
		/* Static helpers only */
	}

	/* Never the group of the node itself, which hosts the chains of that group and joins none of them as a client */
	public static OptionalInt group(ShardGroups groups, Optional<String> country,
		Function<String, Optional<String>> countryOf, OptionalInt own, RandomGenerator random) {

		return group(groups, country, countryOf, own.stream().boxed().collect(Collectors.toSet()), random);
	}

	/* Empty when no group that is not passed over has a member in the country, or when there are no groups */
	public static OptionalInt group(ShardGroups groups, Optional<String> country,
		Function<String, Optional<String>> countryOf, Set<Integer> passedOver, RandomGenerator random) {

		final long[] weights = IntStream.range(0, groups.groupCount()).mapToLong(group -> passedOver.contains(group)
			? 0 : weight(groups.members(group), country, countryOf)).toArray();
		final long total = LongStream.of(weights).sum();

		if (total == 0) {
			return OptionalInt.empty();
		}

		long drawn = random.nextLong(total);
		int group = 0;

		while (drawn >= weights[group]) {
			drawn -= weights[group];
			group++;
		}

		return OptionalInt.of(group);
	}

	/* A member drawn uniformly among those in the country that are not excluded, any of them for no country */
	public static Optional<String> exit(List<String> members, Optional<String> country,
		Function<String, Optional<String>> countryOf, Set<String> excluded, RandomGenerator random) {

		final List<String> eligible = members.stream().distinct().filter(member -> !excluded.contains(member))
			.filter(member -> isIn(member, country, countryOf)).toList();

		return eligible.isEmpty() ? Optional.empty() : Optional.of(eligible.get(random.nextInt(eligible.size())));
	}

	/* The weight of a group: its members in the country, all of them for no country */
	public static long weight(List<String> members, Optional<String> country,
		Function<String, Optional<String>> countryOf) {

		return members.stream().filter(member -> isIn(member, country, countryOf)).count();
	}

	/* No country is looked up when none is asked for */
	private static boolean isIn(String member, Optional<String> country, Function<String, Optional<String>> countryOf) {
		return country.isEmpty() || countryOf.apply(member).equals(country);
	}
}
