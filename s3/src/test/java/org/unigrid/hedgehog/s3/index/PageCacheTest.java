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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.s3.spi.BlobId;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class PageCacheTest {
	private static final BlobId RUN = new BlobId("run");

	private record Entry(long weight) implements PageCache.Weighed {
	}

	@Provide
	Arbitrary<List<Integer>> offsets() {
		return Arbitraries.integers().between(0, 20).list().ofMaxSize(100);
	}

	private static Entry get(final PageCache cache, final int offset, final AtomicInteger loads) throws Exception {
		return cache.get(new PageCache.Address(RUN, offset), Entry.class, () -> {
			loads.incrementAndGet();
			return new Entry(offset + 1);
		});
	}

	@Property
	public void neverHoldsMoreThanItsCapacity(@ForAll("offsets") final List<Integer> offsets,
		@ForAll @IntRange(max = 100) final int capacity) throws Exception {

		final PageCache cache = new PageCache(capacity);

		for (int offset : offsets) {
			assertThat(get(cache, offset, new AtomicInteger()).weight(), equalTo(offset + 1L));
			assertThat(cache.used(), lessThanOrEqualTo((long) capacity));
		}
	}

	@Property
	public void loadsEachEntryOnceWhileEverythingFits(@ForAll("offsets") final List<Integer> offsets)
		throws Exception {

		final PageCache cache = new PageCache(Long.MAX_VALUE);
		final AtomicInteger loads = new AtomicInteger();

		for (int offset : offsets) {
			get(cache, offset, loads);
		}

		assertThat(loads.get(), equalTo(new HashSet<>(offsets).size()));
	}

	@Example
	public void evictsTheLeastRecentlyUsedFirst() throws Exception {
		final PageCache cache = new PageCache(6);
		final AtomicInteger loads = new AtomicInteger();

		for (int offset : new int[] {0, 1, 2, 0, 3}) {
			get(cache, offset, loads);
		}

		loads.set(0);

		for (int offset : Set.of(0, 3)) {
			get(cache, offset, loads);
		}

		assertThat(loads.get(), equalTo(0));
		get(cache, 1, loads);
		assertThat(loads.get(), equalTo(1));
	}
}
