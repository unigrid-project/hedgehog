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
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.s3.spi.MemoryBlobStore;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

public class RunTest {
	private final MemoryBlobStore blobs = new MemoryBlobStore();

	@Provide
	Arbitrary<List<Batch>> batches() {
		return IndexArbitraries.batches().list().ofMinSize(1).ofMaxSize(20);
	}

	@Provide
	Arbitrary<byte[]> keys() {
		return IndexArbitraries.rawKeys();
	}

	private static NavigableMap<byte[], Slot> model(final List<Batch> batches) {
		final NavigableMap<byte[], Slot> model = new TreeMap<>(Arrays::compareUnsigned);

		batches.forEach(batch -> batch.slots().forEach(slot -> model.put(slot.key(), slot)));
		return model;
	}

	private Run run(final NavigableMap<byte[], Slot> model, final int pageBytes, final long cacheBytes)
		throws Exception {

		final NamespaceConfig config = NamespaceConfig.builder().pageBytes(pageBytes).build();
		final RunInfo info = RunWriter.write(Cursor.of(model.values().iterator()), model.size(), true,
			PosixFiles.create().getPath("/"), blobs, config).orElseThrow();

		return new Run(info, blobs, new PageCache(cacheBytes), config.getMaxBlockBytes());
	}

	static List<Slot> drain(final Cursor cursor) throws Exception {
		final List<Slot> slots = new ArrayList<>();

		for (Slot slot = cursor.next(); slot != null; slot = cursor.next()) {
			slots.add(slot);
		}

		return slots;
	}

	@Property(tries = 300)
	public void findsWhatWasWritten(@ForAll("batches") final List<Batch> batches, @ForAll("keys") final byte[] key,
		@ForAll @IntRange(min = 1, max = 64) final int pageBytes, @ForAll @IntRange(max = 512) final int cacheBytes)
		throws Exception {

		final NavigableMap<byte[], Slot> model = model(batches);

		assertThat(run(model, pageBytes, cacheBytes).get(key), equalTo(Optional.ofNullable(model.get(key))));
	}

	@Property(tries = 300)
	public void scansInKeyOrder(@ForAll("batches") final List<Batch> batches, @ForAll("keys") final byte[] from,
		@ForAll("keys") final byte[] to, @ForAll @IntRange(min = 1, max = 64) final int pageBytes,
		@ForAll @IntRange(max = 512) final int cacheBytes) throws Exception {

		final NavigableMap<byte[], Slot> model = model(batches);
		final Run run = run(model, pageBytes, cacheBytes);
		final List<Slot> range = Arrays.compareUnsigned(from, to) < 0
			? List.copyOf(model.subMap(from, true, to, false).values()) : List.of();

		assertThat(drain(run.scan(from, to)), equalTo(range));
		assertThat(drain(run.scan(from, null)), equalTo(List.copyOf(model.tailMap(from, true).values())));
	}

	/* A manifest from a damaged or forged blob may place the index anywhere and claim any length for it */
	@Property(tries = 100)
	public void refusesAnIndexOutsideTheRunOrPastTheBlockLimit(@ForAll("batches") final List<Batch> batches,
		@ForAll final long size, @ForAll final long indexOffset) throws Exception {

		final NavigableMap<byte[], Slot> model = model(batches);
		final RunInfo real = RunWriter.write(Cursor.of(model.values().iterator()), model.size(), true,
			PosixFiles.create().getPath("/"), blobs, NamespaceConfig.builder().build()).orElseThrow();
		final RunInfo forged = new RunInfo(real.id(), size, indexOffset, real.entries(), real.first(), real.last());
		final long blockLimit = 1024;

		Assume.that(size != real.size() || indexOffset != real.indexOffset());
		assertThrows(CorruptIndexException.class, () -> new Run(forged, blobs, new PageCache(0), blockLimit)
			.get(model.firstKey()));
	}
}
