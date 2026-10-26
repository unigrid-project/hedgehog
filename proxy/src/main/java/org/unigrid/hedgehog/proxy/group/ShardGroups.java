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

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.IntStream;

/* The active gridnodes of one epoch, ranked by SHA-256(seed || id) with seed = SHA-256("shard-group" || epoch)
   and dealt round robin into max(1, n / groupSize) groups, so sizes differ by at most one */
public final class ShardGroups {
	private static final byte[] LABEL = "shard-group".getBytes(StandardCharsets.US_ASCII);

	private final List<List<String>> groups;
	private final Map<String, Integer> groupOf = new HashMap<>();

	private ShardGroups(List<List<String>> groups) {
		this.groups = groups;

		for (int group = 0; group < groups.size(); group++) {
			for (String id : groups.get(group)) {
				groupOf.put(id, group);
			}
		}
	}

	public static ShardGroups of(Epoch epoch, int groupSize, Collection<String> gridnodeIds) {
		if (groupSize < 1) {
			throw new IllegalArgumentException("A group holds at least one gridnode, found " + groupSize);
		}

		final List<String> ranked = Rendezvous.rank(Sha256.digest(LABEL, epoch.bytes()), gridnodeIds);
		final int count = ranked.isEmpty() ? 0 : Math.max(1, ranked.size() / groupSize);

		return new ShardGroups(IntStream.range(0, count).mapToObj(group -> IntStream
			.iterate(group, i -> i < ranked.size(), i -> i + count).mapToObj(ranked::get).toList()).toList());
	}

	public int groupCount() {
		return groups.size();
	}

	/* The members of a group in rank order */
	public List<String> members(int group) {
		return groups.get(group);
	}

	public OptionalInt groupOf(String gridnodeId) {
		final Integer group = groupOf.get(gridnodeId);

		return group == null ? OptionalInt.empty() : OptionalInt.of(group);
	}
}
