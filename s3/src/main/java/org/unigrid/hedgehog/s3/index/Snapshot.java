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

/* A consistent view of the tree: the memtable taking writes, the one being flushed if a flush is under way, and the
   runs newest first, all read at a sequence so writes committed after it stay out of sight */
record Snapshot(List<Memtable> memtables, List<Run> runs, long sequence) {
	Snapshot {
		memtables = List.copyOf(memtables);
		runs = List.copyOf(runs);
	}

	Memtable active() {
		return memtables.getFirst();
	}

	Optional<Memtable> frozen() {
		return memtables.stream().skip(1).findFirst();
	}

	Snapshot at(final long next) {
		return new Snapshot(memtables, runs, next);
	}

	Snapshot freeze() {
		return new Snapshot(List.of(new Memtable(), active()), runs, sequence);
	}

	Snapshot thaw(final List<Run> flushed) {
		return new Snapshot(List.of(active()), flushed, sequence);
	}

	Snapshot withRuns(final List<Run> replaced) {
		return new Snapshot(memtables, replaced, sequence);
	}

	/* The value of the key, absent when the key was never written or its newest slot is a tombstone */
	Optional<byte[]> get(final byte[] key) throws IOException, BlobStoreException {
		return slot(key).map(Slot::value);
	}

	private Optional<Slot> slot(final byte[] key) throws IOException, BlobStoreException {
		for (Memtable memtable : memtables) {
			final Optional<Slot> slot = memtable.get(key, sequence);

			if (slot.isPresent()) {
				return slot;
			}
		}

		for (Run run : runs) {
			final Optional<Slot> slot = run.get(key);

			if (slot.isPresent()) {
				return slot;
			}
		}

		return Optional.empty();
	}

	/* The live slots in [from, to), where a null end is open */
	Cursor scan(final byte[] from, final byte[] to) {
		return new MergeCursor(sources(from, to), false);
	}

	List<Cursor> sources(final byte[] from, final byte[] to) {
		final List<Cursor> sources = new ArrayList<>();

		memtables.forEach(memtable -> sources.add(Cursor.of(memtable.scan(from, to, sequence))));
		runs.forEach(run -> sources.add(run.scan(from, to)));
		return sources;
	}
}
