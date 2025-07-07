/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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
import java.util.Optional;

/* The entries of one writer, linked by seq and prev. A writer orders only its own entries, so the
   links are all a feed checks: signatures are the chain's concern. */
public final class Feed {
	/* Nothing follows it, and a feed that took it or started after it would overflow its next sequence number */
	private static final long LAST_SEQ = Long.MAX_VALUE;

	private final WriterId writer;
	private Head head;
	private long nextSeq;
	private long prev;
	private long timestamp = Long.MIN_VALUE;

	public Feed(WriterId writer) {
		this.writer = Objects.requireNonNull(writer, "writer");
	}

	public Feed(WriterId writer, Head head) {
		this(writer);

		if (!canStartAfter(head)) {
			throw new IllegalArgumentException("No entry follows the last sequence number " + head.seq());
		}

		this.head = head;
		nextSeq = head.seq() + 1;
		prev = head.hash();
	}

	public static boolean canStartAfter(Head head) {
		return head.seq() < LAST_SEQ;
	}

	public WriterId writer() {
		return writer;
	}

	public Optional<Head> head() {
		return Optional.ofNullable(head);
	}

	public long nextSeq() {
		return nextSeq;
	}

	public Link check(Entry entry) {
		final EntryHeader header = entry.header();

		if (!writer.equals(header.writer())) {
			return Link.FOREIGN;
		}

		if (header.seq() == LAST_SEQ) {
			return Link.BROKEN;
		}

		if (header.seq() == nextSeq) {
			return follows(header) ? Link.NEXT : Link.BROKEN;
		}

		return header.seq() > nextSeq ? Link.AHEAD : behind(header.seq(), entry.hash());
	}

	public Link append(Entry entry) {
		final Link link = check(entry);

		if (link == Link.NEXT) {
			head = new Head(entry.header().seq(), entry.hash());
			nextSeq = head.seq() + 1;
			prev = head.hash();
			timestamp = entry.header().timestamp();
		}

		return link;
	}

	/* Time never runs backwards within a feed, so the merged chain keeps every feed in seq order */
	private boolean follows(EntryHeader header) {
		return header.prev() == prev && header.timestamp() >= timestamp;
	}

	private Link behind(long seq, long hash) {
		if (head == null || seq != head.seq()) {
			return Link.REPLAYED;
		}

		return hash == head.hash() ? Link.DUPLICATE : Link.BROKEN;
	}
}
