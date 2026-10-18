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
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/* The writes of one mutation, which become visible and durable together */
final class Batch {
	private final NavigableMap<byte[], Slot> slots = new TreeMap<>(Arrays::compareUnsigned);

	void write(final byte[] key, final byte[] value) {
		slots.put(key, new Slot(key, value));
	}

	void put(final byte[] key, final byte[] value) {
		write(key, Objects.requireNonNull(value));
	}

	void delete(final byte[] key) {
		write(key, null);
	}

	Optional<Slot> get(final byte[] key) {
		return Optional.ofNullable(slots.get(key));
	}

	/* A copy of the range, so the transaction can keep writing while it walks what it read */
	Iterator<Slot> scan(final byte[] from, final byte[] to) {
		if (!Keys.before(from, to)) {
			return Collections.emptyIterator();
		}

		return List.copyOf((Objects.isNull(to) ? slots.tailMap(from, true) : slots.subMap(from, true, to, false))
			.values()).iterator();
	}

	Collection<Slot> slots() {
		return slots.values();
	}

	boolean isEmpty() {
		return slots.isEmpty();
	}
}
