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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class GroupViewTest {
	private static final long NOW = 1_750_000_000_000L;
	private static final List<String> GRIDNODES = IntStream.range(0, 20).mapToObj(i -> "gn-" + i).toList();
	private static final List<String> THREE_GROUPS = IntStream.range(0, 24).mapToObj(i -> "gn-" + i).toList();
	private static final ProxySettings SMALL_GROUPS = new ProxySettings(5, 120, 2, 16, 32L * 1024 * 1024, 30,
		16 * 1024, 300, ProxySettings.CONNECT | ProxySettings.UDP);

	private static Fingerprint fingerprint(int value) {
		final byte[] bytes = new byte[Fingerprint.SIZE];

		Arrays.fill(bytes, (byte) value);
		return new Fingerprint(bytes);
	}

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
			Expect.assertThrows(IndexOutOfBoundsException.class,
				() -> view.exit(group, fingerprint(9), Set.of()));
		}
	}

	@Example
	public void shouldHaveNoGroupsChainsOrExitsWithoutGridnodes() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, List.of());

		assertThat(view.groups().groupCount(), is(0));
		assertThat(view.chainsOf("gn-0"), is(empty()));
		Expect.assertThrows(IndexOutOfBoundsException.class, () -> view.chains(0));
		Expect.assertThrows(IndexOutOfBoundsException.class, () -> view.exit(0, fingerprint(9), Set.of()));
	}

	@Property(tries = 50)
	public void shouldOnlyOfferGroupsThatHaveNotRefused(@ForAll Random random,
		@ForAll @IntRange(min = 0, max = 3) int refusedCount) {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, IntStream.range(0, 24)
			.mapToObj(i -> "gn-" + i).toList());
		final Set<Integer> refused = new HashSet<>(IntStream.range(0, refusedCount).boxed().toList());
		final OptionalInt candidate = view.joinCandidate(random, refused);

		assertThat(candidate.isPresent(), is(refusedCount < 3));
		candidate.ifPresent(group -> assertThat(refused.contains(group), is(false)));
	}

	@Example
	public void shouldOfferEveryGroupAtRandom() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, GRIDNODES);
		final Random random = new Random(1);
		final Set<Integer> offered = new HashSet<>();

		for (int i = 0; i < 64; i++) {
			offered.add(view.joinCandidate(random, Set.of()).orElseThrow());
		}

		assertThat(offered, equalTo(Set.of(0, 1)));
		assertThat(new GroupView(ProxySettings.DEFAULTS, NOW, List.of()).joinCandidate(random, Set.of()),
			equalTo(OptionalInt.empty()));
	}

	@Example
	public void shouldOfferEveryOfThreeGroupsAtRandom() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, THREE_GROUPS);
		final Random random = new Random(7);
		final Set<Integer> offered = new HashSet<>();

		for (int i = 0; i < 64; i++) {
			offered.add(view.joinCandidate(random, Set.of()).orElseThrow());
		}

		assertThat(view.groups().groupCount(), is(3));
		assertThat(offered, equalTo(Set.of(0, 1, 2)));
	}

	@Example
	public void shouldOfferEveryGroupThatIsLeftOpenAtRandom() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, THREE_GROUPS);
		final Random random = new Random(5);
		final Set<Integer> offered = new HashSet<>();

		for (int i = 0; i < 64; i++) {
			offered.add(view.joinCandidate(random, Set.of(1)).orElseThrow());
		}

		assertThat(offered, equalTo(Set.of(0, 2)));
		assertThat(view.joinCandidate(random, Set.of(0, 2, 5, -1)), equalTo(OptionalInt.of(1)));
		assertThat(view.joinCandidate(random, Set.of(0, 1, 2, 5, -1)), equalTo(OptionalInt.empty()));
	}

	@Example
	public void shouldChooseTheExitAmongTheMembersOfTheGroup() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, GRIDNODES);
		final Optional<String> exit = view.exit(0, fingerprint(9), Set.of());

		assertThat(view.groups().members(0), hasItem(exit.orElseThrow()));
		assertThat(exit, equalTo(ExitChoice.exit(fingerprint(9), view.groups().members(0), Set.of())));
		assertThat(view.exit(0, fingerprint(9), Set.of(exit.get())), not(equalTo(exit)));
	}

	@Example
	public void shouldChooseTheExitOfTheGroupAskedFor() {
		final GroupView view = new GroupView(ProxySettings.DEFAULTS, NOW, GRIDNODES);
		final Optional<String> exit = view.exit(1, fingerprint(9), Set.of());

		assertThat(view.groups().members(1), hasItem(exit.orElseThrow()));
		assertThat(exit, equalTo(ExitChoice.exit(fingerprint(9), view.groups().members(1), Set.of())));
		assertThat(view.exit(1, fingerprint(9), new HashSet<>(view.groups().members(1))), equalTo(Optional.empty()));
	}
}
