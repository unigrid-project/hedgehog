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

package org.unigrid.hedgehog.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.storage.Fingerprint;
import org.unigrid.hedgehog.s3.spi.Blob;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobNotFoundException;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;
import org.unigrid.hedgehog.s3.spi.BlobUnavailableException;

@RequiredArgsConstructor
public class StorageBlobStore implements BlobStore {
	private final StorageService storage;

	@Override
	public BlobId store(final InputStream input) throws IOException, BlobStoreException {
		try {
			return new BlobId(storage.store(input).encode());
		} catch (StorageException ex) {
			throw translate(ex);
		}
	}

	@Override
	public Blob open(final BlobId id) throws BlobStoreException {
		try {
			return new StoredBlob(storage.open(fingerprint(id)));
		} catch (StorageException ex) {
			throw translate(ex);
		}
	}

	@Override
	public void delete(final BlobId id) throws BlobStoreException {
		try {
			storage.delete(fingerprint(id));
		} catch (StorageException ex) {
			throw translate(ex);
		}
	}

	private static Fingerprint fingerprint(final BlobId id) throws BlobNotFoundException {
		try {
			return Fingerprint.parse(id.value());
		} catch (IllegalArgumentException ex) {
			throw new BlobNotFoundException("Not the id of a stored blob", ex);
		}
	}

	private static BlobStoreException translate(final StorageException ex) {
		return switch (ex) {
			case InsufficientGridnodesException _, StorageDisabledException _ ->
				new BlobUnavailableException(ex.getMessage(), ex);
			case FingerprintNotFoundException _ -> new BlobNotFoundException(ex.getMessage(), ex);
			default -> new BlobStoreException(ex.getMessage(), ex);
		};
	}

	private record StoredBlob(Retrieval retrieval) implements Blob {
		@Override
		public long size() {
			return retrieval.size();
		}

		@Override
		public void writeTo(final OutputStream output) throws IOException, BlobStoreException {
			try {
				retrieval.writeTo(output);
			} catch (StorageException ex) {
				throw translate(ex);
			}
		}
	}
}
