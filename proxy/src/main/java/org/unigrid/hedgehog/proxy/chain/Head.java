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

/* The latest entry of a feed: its sequence number and hash, which name the entry, and its stamp and whether it ended
   the feed, which a feed started after it takes over. Heads are equal when they name the same entry. The hash of an
   entry covers its stamp and mark, so the heads a chain takes from its own feeds agree on them; for a head a peer
   sends, the stamp and the mark are that peer's word, as the head itself is. A head that only names its entry has
   no stamp and ends nothing. */
public record Head(long seq, long hash, long timestamp, boolean last) {
	public Head {
		if (seq < 0) {
			throw new IllegalArgumentException("A sequence number is never negative, found " + seq);
		}
	}

	public Head(long seq, long hash) {
		this(seq, hash, Long.MIN_VALUE, false);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Head head && seq == head.seq && hash == head.hash;
	}

	@Override
	public int hashCode() {
		return 31 * Long.hashCode(seq) + Long.hashCode(hash);
	}
}
