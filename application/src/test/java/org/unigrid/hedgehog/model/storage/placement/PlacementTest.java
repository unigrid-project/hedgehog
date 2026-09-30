/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.storage.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.storage.GroupId;

public class PlacementTest {
	private static List<Gridnode> gridnodes(int count) {
		return IntStream.range(0, count).mapToObj(i -> Gridnode.builder().id("gridnode-" + i)
			.hostName("10.0.0." + i + ":52883").status(Gridnode.Status.ACTIVE).build()).collect(Collectors.toList());
	}

	private static GroupId group(long seed) {
		final byte[] id = new byte[GroupId.SIZE];
		new Random(seed).nextBytes(id);
		return GroupId.of(id);
	}

	@Property(tries = 100)
	public void ignoresInputOrder(@ForAll @IntRange(min = 1, max = 60) int count, @ForAll long seed) {
		final List<Gridnode> nodes = gridnodes(count);
		final List<Gridnode> shuffled = new ArrayList<>(nodes);
		Collections.shuffle(shuffled, new Random(seed));

		assertThat(Placement.rank(group(seed), shuffled), equalTo(Placement.rank(group(seed), nodes)));
	}

	@Property(tries = 100)
	public void windowsHoldDistinctGridnodes(@ForAll @IntRange(min = 1, max = 60) int count,
		@ForAll @IntRange(min = 1, max = 80) int width, @ForAll long seed) {

		final List<Gridnode> window = Placement.window(group(seed), gridnodes(count), width);

		assertThat(window.size(), equalTo(Math.min(width, count)));
		assertThat(new HashSet<>(window).size(), equalTo(window.size()));
	}

	@Property(tries = 100)
	public void keepsTheOrderOfExistingGridnodesWhenOneJoins(@ForAll @IntRange(min = 2, max = 60) int count,
		@ForAll long seed) {

		final List<Gridnode> before = gridnodes(count - 1);
		final List<Gridnode> after = gridnodes(count);
		final Gridnode joined = after.get(count - 1);
		final List<Gridnode> rankedAfter = new ArrayList<>(Placement.rank(group(seed), after));
		rankedAfter.remove(joined);

		assertThat(rankedAfter, equalTo(Placement.rank(group(seed), before)));
	}

	@Property(tries = 20)
	public void spreadsGroupsOverDifferentGridnodes(@ForAll long seed) {
		final List<Gridnode> nodes = gridnodes(10);
		final long distinctLeaders = IntStream.range(0, 50)
			.mapToObj(i -> Placement.rank(group(seed + i), nodes).get(0)).distinct().count();

		assertThat(distinctLeaders, greaterThan(3L));
	}

	@Property(tries = 50)
	public void findsTheRankOfAGridnode(@ForAll @IntRange(min = 1, max = 30) int count, @ForAll long seed) {
		final List<Gridnode> ranked = Placement.rank(group(seed), gridnodes(count));

		for (int i = 0; i < ranked.size(); i++) {
			assertThat(Placement.rankOf(group(seed), ranked, ranked.get(i).getId()).getAsInt(), equalTo(i));
		}
	}
}
