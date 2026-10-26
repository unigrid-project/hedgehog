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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class MemtableTest {
	@Provide
	Arbitrary<List<Batch>> batches() {
		return IndexArbitraries.batches().list().ofMaxSize(12);
	}

	@Provide
	Arbitrary<byte[]> keys() {
		return IndexArbitraries.rawKeys();
	}

	private static Memtable memtable(final List<Batch> batches) {
		final Memtable memtable = new Memtable();

		for (int i = 0; i < batches.size(); i++) {
			memtable.apply(i + 1, batches.get(i));
		}

		return memtable;
	}

	/* What every key held once the batches up to the snapshot were applied, tombstones included */
	private static NavigableMap<byte[], Slot> model(final List<Batch> batches, final int snapshot) {
		final NavigableMap<byte[], Slot> model = new TreeMap<>(Arrays::compareUnsigned);

		batches.subList(0, Math.min(snapshot, batches.size())).forEach(batch -> batch.slots()
			.forEach(slot -> model.put(slot.key(), slot)));

		return model;
	}

	private static List<Slot> drain(final Iterator<Slot> slots) {
		final List<Slot> drained = new ArrayList<>();

		slots.forEachRemaining(drained::add);
		return drained;
	}

	@Property
	public void readsWhatTheLastWriteBeforeTheSnapshotLeft(@ForAll("batches") final List<Batch> batches,
		@ForAll @IntRange(max = 12) final int snapshot, @ForAll("keys") final byte[] key) {

		assertThat(memtable(batches).get(key, snapshot),
			equalTo(Optional.ofNullable(model(batches, snapshot).get(key))));
	}

	@Property
	public void scansTheSnapshotInKeyOrder(@ForAll("batches") final List<Batch> batches,
		@ForAll @IntRange(max = 12) final int snapshot, @ForAll("keys") final byte[] from,
		@ForAll("keys") final byte[] to) {

		final NavigableMap<byte[], Slot> model = model(batches, snapshot);
		final List<Slot> expected = Arrays.compareUnsigned(from, to) < 0
			? List.copyOf(model.subMap(from, true, to, false).values()) : List.of();

		assertThat(drain(memtable(batches).scan(from, to, snapshot)), equalTo(expected));
		assertThat(drain(memtable(batches).scan(from, null, snapshot)),
			equalTo(List.copyOf(model.tailMap(from, true).values())));
	}
}
