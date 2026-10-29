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
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class GroupViewTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final List<String> GRIDNODES = IntStream.range(0, 20).mapToObj(i -> "gn-" + i).toList();
	private static final ProxySettings SMALL_GROUPS = new ProxySettings(5, 120, 2, 16, 32L * 1024 * 1024, 30,
		16 * 1024, 300, ProxySettings.CONNECT | ProxySettings.UDP);

	@Example
	public void shouldDeriveGroupsAndChainsFromTheSettingsAndTime() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, GRIDNODES);

		assertThat(view.epoch(), equalTo(new Epoch(486_111)));
		assertThat(view.groups().groupCount(), is(2));
		assertThat(view.chains(1), equalTo(ChainIds.ofGroup(new Epoch(486_111), 1, 4)));
	}

	@Example
	public void shouldFollowThePeriodGroupSizeAndChainCountOfTheSettings() {
		final GroupView view = new GroupView(SMALL_GROUPS, NOW, GRIDNODES);

		assertThat(view.epoch(), equalTo(new Epoch(14_583_333)));
		assertThat(view.groups().groupCount(), is(4));
		assertThat(view.chains(3), equalTo(ChainIds.ofGroup(new Epoch(14_583_333), 3, 2)));
		assertThat(view.chains(3), hasSize(2));
	}

	@Example
	public void shouldRefuseMissingSettings() {
		final NullPointerException refused = Expect.assertThrows(NullPointerException.class,
			() -> new GroupView(null, NOW, GRIDNODES));

		assertThat(refused.getMessage(), is("settings"));
	}

	@Property(tries = 50)
	public void shouldAgreeOnEveryNodeWithinOneEpoch(@ForAll @LongRange(min = 0, max = 3_599_999) long offset) {
		final GroupView early = new GroupView(ProxySettings.DEFAULTS, 1_749_999_600_000L, GRIDNODES);
		final GroupView late = new GroupView(ProxySettings.DEFAULTS, 1_749_999_600_000L + offset, GRIDNODES);

		for (String gridnode : GRIDNODES) {
			assertThat(late.chainsOf(gridnode), equalTo(early.chainsOf(gridnode)));
		}
	}

	@Example
	public void shouldHostTheChainsOfItsOwnGroupOnly() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, GRIDNODES);
		final int group = view.groups().groupOf("gn-7").orElseThrow();

		assertThat(view.chainsOf("gn-7"), equalTo(view.chains(group)));
		assertThat(view.chainsOf("gn-7"), not(equalTo(view.chains(1 - group))));
		assertThat(view.chainsOf("not-active"), is(empty()));
	}

	@Example
	public void shouldHostTheChainsOfTheGroupOfEveryActiveNode() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, GRIDNODES);

		for (String gridnode : GRIDNODES) {
			final int group = view.groups().groupOf(gridnode).orElseThrow();

			assertThat(view.chainsOf(gridnode), equalTo(view.chains(group)));
		}

		assertThat(GRIDNODES.stream().map(view::chainsOf).distinct().toList(), hasSize(2));
	}

	@Example
	public void shouldPartitionTheGridnodesOfItsOwnEpoch() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, GRIDNODES);
		final ShardGroups sameEpoch = ShardGroups.of(new Epoch(486_111), 8, GRIDNODES);
		final ShardGroups nextEpoch = ShardGroups.of(new Epoch(486_112), 8, GRIDNODES);

		for (int group = 0; group < 2; group++) {
			assertThat(view.groups().members(group), equalTo(sameEpoch.members(group)));
			assertThat(view.groups().members(group), not(equalTo(nextEpoch.members(group))));
		}
	}

	@Example
	public void shouldRefuseAGroupThatDoesNotExist() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, GRIDNODES);

		for (int group : new int[] { -1, 2, 3, Integer.MAX_VALUE, Integer.MIN_VALUE }) {
			Expect.assertThrows(IndexOutOfBoundsException.class, () -> view.chains(group));
		}
	}

	@Example
	public void shouldHaveNoGroupsOrChainsWithoutGridnodes() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, List.of());

		assertThat(view.groups().groupCount(), is(0));
		assertThat(view.chainsOf("gn-0"), is(empty()));
		Expect.assertThrows(IndexOutOfBoundsException.class, () -> view.chains(0));
	}
}
