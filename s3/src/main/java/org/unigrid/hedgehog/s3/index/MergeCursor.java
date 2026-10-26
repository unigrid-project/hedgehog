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

package org.unigrid.hedgehog.s3.index;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Merges cursors given newest first into one in key order. Of the slots sharing a key the newest wins and the rest
   are skipped, and a winning tombstone is dropped unless tombstones are kept. */
final class MergeCursor implements Cursor {
	private record Head(Slot slot, int rank, Cursor cursor) {
		static final Comparator<Head> ORDER = Comparator.<Head, byte[]>comparing(head -> head.slot().key(),
			Arrays::compareUnsigned).thenComparingInt(Head::rank);
	}

	private final List<Cursor> sources;
	private final boolean tombstones;
	private final PriorityQueue<Head> heads = new PriorityQueue<>(Head.ORDER);
	private boolean started;

	MergeCursor(final List<Cursor> sources, final boolean tombstones) {
		this.sources = sources;
		this.tombstones = tombstones;
	}

	@Override
	public Slot next() throws IOException, BlobStoreException {
		if (!started) {
			for (int rank = 0; rank < sources.size(); rank++) {
				advance(rank, sources.get(rank));
			}

			started = true;
		}

		while (!heads.isEmpty()) {
			final Head head = heads.poll();

			advance(head.rank(), head.cursor());
			skipOlder(head.slot().key());

			if (tombstones || !head.slot().tombstone()) {
				return head.slot();
			}
		}

		return null;
	}

	private void skipOlder(final byte[] key) throws IOException, BlobStoreException {
		while (!heads.isEmpty() && Arrays.equals(heads.peek().slot().key(), key)) {
			final Head older = heads.poll();

			advance(older.rank(), older.cursor());
		}
	}

	private void advance(final int rank, final Cursor cursor) throws IOException, BlobStoreException {
		final Slot slot = cursor.next();

		if (Objects.nonNull(slot)) {
			heads.add(new Head(slot, rank, cursor));
		}
	}
}
