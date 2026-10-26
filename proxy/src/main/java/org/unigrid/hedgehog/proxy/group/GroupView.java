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

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;

/* The shard groups and chains one node sees for the settings, time and active gridnodes it was given */
public final class GroupView {
	private final ProxySettings settings;
	private final Epoch epoch;
	private final ShardGroups groups;

	public GroupView(ProxySettings settings, long epochMillis, Collection<String> gridnodeIds) {
		this.settings = Objects.requireNonNull(settings, "settings");
		epoch = settings.epochAt(epochMillis);
		groups = ShardGroups.of(epoch, settings.groupSize(), gridnodeIds);
	}

	public Epoch epoch() {
		return epoch;
	}

	public ShardGroups groups() {
		return groups;
	}

	public List<ChainId> chains(int group) {
		/* A group that does not exist gets no invented chain ids, and the exception is the one members throws */
		Objects.checkIndex(group, groups.groupCount());

		return ChainIds.ofGroup(epoch, group, settings.chainsPerGroup());
	}

	/* The chains a gridnode hosts: those of its own group, none when it is not active */
	public List<ChainId> chainsOf(String gridnodeId) {
		final OptionalInt group = groups.groupOf(gridnodeId);

		return group.isPresent() ? chains(group.getAsInt()) : List.of();
	}

	/* A group for a client to join, chosen at random among those that have not refused it yet. Empty when every
	   group has refused it and when there are no gridnodes, hence no groups, at all. */
	public OptionalInt joinCandidate(RandomGenerator random, Set<Integer> refused) {
		final int[] open = IntStream.range(0, groups.groupCount()).filter(group -> !refused.contains(group))
			.toArray();

		return open.length == 0 ? OptionalInt.empty() : OptionalInt.of(open[random.nextInt(open.length)]);
	}

	public Optional<String> exit(int group, Fingerprint fingerprint, Set<String> excluded) {
		return ExitChoice.exit(fingerprint, groups.members(group), excluded);
	}
}
