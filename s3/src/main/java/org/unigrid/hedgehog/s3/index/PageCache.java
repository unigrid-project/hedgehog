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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Decoded run pages and indices by their place in the run, least recently used first out once the weights of the
   entries pass the capacity. Two readers missing the same entry both load it, which costs a read but no lock. */
final class PageCache {
	private static final int INITIAL_CAPACITY = 64;
	private static final float LOAD_FACTOR = 0.75f;

	interface Weighed {
		long weight();
	}

	@FunctionalInterface
	interface Loader<T extends Weighed> {
		T load() throws IOException, BlobStoreException;
	}

	record Address(BlobId run, long offset) {
	}

	private final LinkedHashMap<Address, Weighed> entries = new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR, true);
	private final long capacity;
	private long used;

	PageCache(final long capacity) {
		this.capacity = capacity;
	}

	<T extends Weighed> T get(final Address address, final Class<T> type, final Loader<T> loader)
		throws IOException, BlobStoreException {

		final Weighed cached = cached(address);

		if (Objects.nonNull(cached)) {
			return type.cast(cached);
		}

		final T loaded = loader.load();

		remember(address, loaded);
		return loaded;
	}

	synchronized long used() {
		return used;
	}

	private synchronized Weighed cached(final Address address) {
		return entries.get(address);
	}

	private synchronized void remember(final Address address, final Weighed value) {
		if (value.weight() <= capacity) {
			final Weighed previous = entries.put(address, value);

			used += value.weight() - (Objects.isNull(previous) ? 0 : previous.weight());

			for (Iterator<Weighed> eldest = entries.values().iterator(); used > capacity;) {
				used -= eldest.next().weight();
				eldest.remove();
			}
		}
	}
}
