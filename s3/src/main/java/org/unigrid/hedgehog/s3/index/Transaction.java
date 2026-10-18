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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* One mutation under the writer lock: it reads the snapshot it started from together with its own writes, and its
   writes commit as one batch at the sequence after that snapshot */
final class Transaction implements View {
	private final Snapshot snapshot;
	private final Batch batch = new Batch();

	Transaction(final Snapshot snapshot) {
		this.snapshot = snapshot;
	}

	long sequence() {
		return snapshot.sequence() + 1;
	}

	Batch batch() {
		return batch;
	}

	@Override
	public Optional<byte[]> get(final byte[] key) throws IOException, BlobStoreException {
		final Optional<Slot> own = batch.get(key);
		return own.isPresent() ? Optional.ofNullable(own.get().value()) : snapshot.get(key);
	}

	@Override
	public Cursor scan(final byte[] from, final byte[] to) {
		final List<Cursor> sources = new ArrayList<>();

		sources.add(Cursor.of(batch.scan(from, to)));
		sources.addAll(snapshot.sources(from, to));
		return new MergeCursor(sources, false);
	}

	void put(final byte[] key, final byte[] value) {
		batch.put(key, value);
	}

	void delete(final byte[] key) {
		batch.delete(key);
	}
}
