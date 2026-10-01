/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.storage.placement;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.crypto.Hashes;

public final class Placement {
	private record Scored(byte[] score, Gridnode gridnode) {
		/* Pairs a gridnode with its rendezvous score for one group */
	}

	private static final Comparator<Scored> HIGHEST_SCORE_FIRST = Comparator
		.<Scored, byte[]>comparing(Scored::score, Arrays::compareUnsigned).reversed()
		.thenComparing(scored -> scored.gridnode().getId());

	private Placement() {
		/* Static helpers only */
	}

	public static List<Gridnode> rank(GroupId groupId, Collection<Gridnode> gridnodes) {
		final byte[] group = groupId.bytes();

		return gridnodes.stream().map(gridnode -> new Scored(score(group, gridnode), gridnode))
			.sorted(HIGHEST_SCORE_FIRST).map(Scored::gridnode).collect(Collectors.toList());
	}

	public static List<Gridnode> window(GroupId groupId, Collection<Gridnode> gridnodes, int width) {
		final List<Gridnode> ranked = rank(groupId, gridnodes);
		return List.copyOf(ranked.subList(0, Math.min(width, ranked.size())));
	}

	public static OptionalInt rankOf(GroupId groupId, Collection<Gridnode> gridnodes, String gridnodeId) {
		final List<Gridnode> ranked = rank(groupId, gridnodes);
		return IntStream.range(0, ranked.size()).filter(i -> ranked.get(i).getId().equals(gridnodeId)).findFirst();
	}

	private static byte[] score(byte[] group, Gridnode gridnode) {
		return Hashes.sha256(group, gridnode.getId().getBytes(StandardCharsets.UTF_8));
	}
}
