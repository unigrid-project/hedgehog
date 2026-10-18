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

package org.unigrid.hedgehog.s3.spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryBlobStore implements BlobStore {
	private final Map<BlobId, byte[]> blobs = new ConcurrentHashMap<>();

	@Override
	public BlobId store(final InputStream input) throws IOException {
		final BlobId id = new BlobId(UUID.randomUUID().toString());

		blobs.put(id, input.readAllBytes());
		return id;
	}

	@Override
	public Blob open(final BlobId id) throws BlobNotFoundException {
		return new MemoryBlob(Optional.ofNullable(blobs.get(id)).orElseThrow(BlobNotFoundException::new));
	}

	@Override
	public void delete(final BlobId id) throws BlobNotFoundException {
		if (Objects.isNull(blobs.remove(id))) {
			throw new BlobNotFoundException();
		}
	}

	public int size() {
		return blobs.size();
	}

	private record MemoryBlob(byte[] content) implements Blob {
		@Override
		public long size() {
			return content.length;
		}

		@Override
		public void writeTo(final OutputStream output) throws IOException {
			output.write(content);
		}
	}
}
