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

package org.unigrid.hedgehog.s3.cache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.s3.spi.Blob;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobNotFoundException;
import org.unigrid.hedgehog.s3.spi.MemoryBlobStore;

/* Counts the blobs opened, and can hold every open back until enough callers have arrived */
public class CountingBlobStore extends MemoryBlobStore {
	private static final long PATIENCE_SECONDS = 10;

	private final AtomicInteger opens = new AtomicInteger();
	private CountDownLatch arrivals = new CountDownLatch(0);

	public int opens() {
		return opens.get();
	}

	public void holdOpensFor(final CountDownLatch latch) {
		arrivals = latch;
	}

	@SneakyThrows(InterruptedException.class)
	@Override
	public Blob open(final BlobId id) throws BlobNotFoundException {
		opens.incrementAndGet();
		arrivals.await(PATIENCE_SECONDS, TimeUnit.SECONDS);
		return super.open(id);
	}
}
