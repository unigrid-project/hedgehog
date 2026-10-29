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

import java.util.Objects;

/* What every member sees of an entry: whose feed it is on which chain, where it links in, when it was written, the
   tag only the ends of its session can place and whether it is the last entry of its feed, after which the chain
   takes nothing more of the writer. Nothing says what kind of message it carries. */
public record EntryHeader(ChainId chainId, WriterId writer, long seq, long prev, long timestamp, Tag tag,
	boolean last) {

	public EntryHeader {
		Objects.requireNonNull(chainId, "chainId");
		Objects.requireNonNull(writer, "writer");
		Objects.requireNonNull(tag, "tag");

		if (seq < 0) {
			throw new IllegalArgumentException("A sequence number is never negative, found " + seq);
		}
	}

	/* An entry that more of its feed may follow */
	public EntryHeader(ChainId chainId, WriterId writer, long seq, long prev, long timestamp, Tag tag) {
		this(chainId, writer, seq, prev, timestamp, tag, false);
	}
}
