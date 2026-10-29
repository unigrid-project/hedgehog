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

public final class FeedWriter {
	/* A feed refuses an entry at this sequence number, so none is signed and the counter never wraps */
	private static final long LAST_SEQ = Long.MAX_VALUE;

	private final WriterKey key;
	private final ChainId chainId;
	private long nextSeq;
	private long prev;
	private long timestamp = Long.MIN_VALUE;
	private boolean ended;

	public FeedWriter(WriterKey key, ChainId chainId) {
		this.key = Objects.requireNonNull(key, "key");
		this.chainId = Objects.requireNonNull(chainId, "chainId");
	}

	FeedWriter(WriterKey key, ChainId chainId, Head after) {
		this(key, chainId);

		if (!Feed.canStartAfter(after)) {
			throw new IllegalArgumentException("No entry follows the last sequence number " + after.seq());
		}

		nextSeq = after.seq() + 1;
		prev = after.hash();
	}

	public WriterId writer() {
		return key.id();
	}

	public ChainId chainId() {
		return chainId;
	}

	/* The stamp of the latest entry the writer signed, Long.MIN_VALUE before the first */
	public synchronized long timestamp() {
		return timestamp;
	}

	/* The clock is in epoch milliseconds and the stamp in whole seconds of it, rounded down: within one second, the
	   time between two entries tells no one how far apart their writers are. A feed refuses time that runs
	   backwards, so a clock that goes back holds the last timestamp. The lock is the writer's own monitor so that a
	   caller can synchronize on the writer to write and post as one step. The state moves only once the entry is
	   signed, so a refused write leaves the writer as it was */
	public synchronized Entry write(long nowMillis, Tag tag, byte[] payload) {
		return write(nowMillis, tag, payload, false);
	}

	/* The last entry of the feed, which says so: a chain takes nothing the writer signs after it, so the writer signs
	   nothing more */
	public synchronized Entry writeLast(long nowMillis, Tag tag, byte[] payload) {
		return write(nowMillis, tag, payload, true);
	}

	private Entry write(long nowMillis, Tag tag, byte[] payload, boolean last) {
		if (ended) {
			throw new IllegalStateException("The feed of the writer has ended");
		}

		if (nextSeq == LAST_SEQ) {
			throw new IllegalStateException("The writer has used every sequence number of its feed");
		}

		final long held = Math.max(timestamp, nowMillis - Math.floorMod(nowMillis, Entry.STAMP_MILLIS));
		final Entry entry = key.sign(new EntryHeader(chainId, key.id(), nextSeq, prev, held, tag, last), payload);

		timestamp = held;
		nextSeq++;
		prev = entry.hash();
		ended = last;
		return entry;
	}
}
