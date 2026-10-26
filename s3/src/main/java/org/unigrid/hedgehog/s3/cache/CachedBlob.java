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

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.spi.Blob;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Read from the cache, and fetched into it again should it have been evicted since it was opened */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class CachedBlob implements Blob {
	private final CachingBlobStore store;
	private final BlobId id;
	private final long size;

	@Override
	public long size() {
		return size;
	}

	@Override
	public void writeTo(final OutputStream output) throws IOException, BlobStoreException {
		writeTo(output, 0, size);
	}

	@Override
	public void writeTo(final OutputStream output, final long offset, final long length)
		throws IOException, BlobStoreException {

		Objects.checkFromIndexSize(offset, length, size);

		try (CacheLease lease = lease()) {
			lease.writeTo(output, offset, length);
		}
	}

	public CacheLease lease() throws IOException, BlobStoreException {
		return store.lease(id);
	}
}
