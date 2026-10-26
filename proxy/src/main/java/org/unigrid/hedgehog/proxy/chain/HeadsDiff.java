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

package org.unigrid.hedgehog.proxy.chain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HeadsDiff {
	private static final long NO_ENTRY = -1;

	private HeadsDiff() {
		/* Static helpers only */
	}

	/* Per writer, ordered by writer, the entries a peer's heads show past the local ones. Each range starts
	   right after the local head and spans at most maxCount entries, so a fetch stays small and a feed fills
	   in order. Swap the arguments to learn what the peer lacks. */
	public static List<MissingRange> missing(Map<WriterId, Head> local, Map<WriterId, Head> remote, int maxCount) {
		Objects.requireNonNull(local, "local");
		Objects.requireNonNull(remote, "remote");

		if (maxCount < 1) {
			throw new IllegalArgumentException("A range spans at least one entry, found " + maxCount);
		}

		return remote.entrySet().stream().filter(head -> seqOf(local, head.getKey()) < head.getValue().seq())
			.sorted(Map.Entry.comparingByKey()).map(head -> range(head.getKey(), seqOf(local, head.getKey()) + 1,
				head.getValue().seq(), maxCount)).toList();
	}

	private static long seqOf(Map<WriterId, Head> heads, WriterId writer) {
		final Head head = heads.get(writer);

		return head == null ? NO_ENTRY : head.seq();
	}

	private static MissingRange range(WriterId writer, long fromSeq, long toSeq, int maxCount) {
		return new MissingRange(writer, fromSeq, fromSeq + Math.min(toSeq - fromSeq, maxCount - 1));
	}
}
