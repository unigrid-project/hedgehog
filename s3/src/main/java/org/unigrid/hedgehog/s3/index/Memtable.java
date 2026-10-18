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

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/* Keeps every write with the sequence of its batch until the next flush, so a reader holding a snapshot sequence
   skips writes that landed after it and sees whole batches only */
final class Memtable {
	private static final int SLOT_OVERHEAD = 64;

	private final ConcurrentSkipListMap<Stamp, Slot> slots = new ConcurrentSkipListMap<>(Stamp.ORDER);
	private final AtomicLong bytes = new AtomicLong();

	private record Stamp(byte[] key, long sequence) {
		static final Comparator<Stamp> ORDER = Comparator
			.<Stamp, byte[]>comparing(Stamp::key, Arrays::compareUnsigned)
			.thenComparing(Stamp::sequence, Comparator.reverseOrder());
	}

	void apply(final long sequence, final Batch batch) {
		for (Slot slot : batch.slots()) {
			slots.put(new Stamp(slot.key(), sequence), slot);
			bytes.addAndGet(slot.key().length + (slot.tombstone() ? 0 : slot.value().length) + SLOT_OVERHEAD);
		}
	}

	Optional<Slot> get(final byte[] key, final long snapshot) {
		final Map.Entry<Stamp, Slot> entry = slots.ceilingEntry(new Stamp(key, snapshot));

		if (Objects.isNull(entry) || !Arrays.equals(entry.getKey().key(), key)) {
			return Optional.empty();
		}

		return Optional.of(entry.getValue());
	}

	/* The newest slot of every key in [from, to) at the snapshot, with tombstones, where a null bound is open */
	Iterator<Slot> scan(final byte[] from, final byte[] to, final long snapshot) {
		if (!Keys.before(from, to)) {
			return Collections.emptyIterator();
		}

		final Stamp start = new Stamp(from, Long.MAX_VALUE);
		final Iterator<Map.Entry<Stamp, Slot>> entries = (Objects.isNull(to) ? slots.tailMap(start, true)
			: slots.subMap(start, true, new Stamp(to, Long.MAX_VALUE), false)).entrySet().iterator();

		return new Lookahead<>() {
			private byte[] previous;

			@Override
			protected Slot advance() {
				while (entries.hasNext()) {
					final Map.Entry<Stamp, Slot> entry = entries.next();
					final byte[] key = entry.getKey().key();

					if (entry.getKey().sequence() <= snapshot && !Arrays.equals(key, previous)) {
						previous = key;
						return entry.getValue();
					}
				}

				return null;
			}
		};
	}

	long bytes() {
		return bytes.get();
	}

	/* Every slot written, overwritten ones included, which bounds the keys a flush writes */
	long count() {
		return slots.size();
	}

	boolean isEmpty() {
		return slots.isEmpty();
	}
}
