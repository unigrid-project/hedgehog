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
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.unigrid.hedgehog.s3.spi.Blob;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobNotFoundException;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobUnavailableException;
import org.unigrid.hedgehog.s3.spi.MemoryBlobStore;

/* A memory store that can be told to fail a coming store call, the way a store with too few nodes online does */
public class FlakyBlobStore implements BlobStore {
	private final MemoryBlobStore blobs = new MemoryBlobStore();
	private final AtomicInteger storesUntilFailure = new AtomicInteger(-1);

	public void failStoreAfter(final int successes) {
		storesUntilFailure.set(successes);
	}

	public void heal() {
		storesUntilFailure.set(-1);
	}

	@Override
	public BlobId store(final InputStream input) throws IOException, BlobUnavailableException {
		if (storesUntilFailure.getAndUpdate(count -> count >= 0 ? count - 1 : count) == 0) {
			throw new BlobUnavailableException("Too few storage nodes online");
		}

		return blobs.store(input);
	}

	@Override
	public Blob open(final BlobId id) throws BlobNotFoundException {
		return blobs.open(id);
	}

	@Override
	public void delete(final BlobId id) throws BlobNotFoundException {
		blobs.delete(id);
	}
}
