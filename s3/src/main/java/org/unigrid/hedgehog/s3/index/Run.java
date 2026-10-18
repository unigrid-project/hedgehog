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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.unigrid.hedgehog.s3.spi.Blob;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Reads a run blob through ranged reads, which a caching blob store can answer locally, and keeps what it decodes
   in the page cache */
final class Run {
	private final RunInfo info;
	private final BlobStore blobs;
	private final PageCache cache;
	private final long maxBlockBytes;

	Run(final RunInfo info, final BlobStore blobs, final PageCache cache, final long maxBlockBytes) {
		this.info = info;
		this.blobs = blobs;
		this.cache = cache;
		this.maxBlockBytes = maxBlockBytes;
	}

	RunInfo info() {
		return info;
	}

	/* The slot of the key, tombstones included */
	Optional<Slot> get(final byte[] key) throws IOException, BlobStoreException {
		if (!info.covers(key)) {
			return Optional.empty();
		}

		final RunIndex index = index();

		if (!index.bloom().mightContain(key)) {
			return Optional.empty();
		}

		final Page page = page(index, index.page(key));
		final int position = page.find(key);

		if (position < page.size() && Arrays.equals(page.keys()[position], key)) {
			return Optional.of(page.slot(position));
		}

		return Optional.empty();
	}

	/* The slots in [from, to), tombstones included, where a null end is open */
	Cursor scan(final byte[] from, final byte[] to) {
		return info.overlaps(from, to) ? new RunCursor(from, to) : () -> null;
	}

	private RunIndex index() throws IOException, BlobStoreException {
		final long length = info.size() - info.indexOffset();

		return cache.get(new PageCache.Address(info.id(), info.indexOffset()), RunIndex.class,
			() -> RunIndex.decode(read(info.indexOffset(), length)));
	}

	private Page page(final RunIndex index, final int number) throws IOException, BlobStoreException {
		final long offset = index.offsets()[number];

		return cache.get(new PageCache.Address(info.id(), offset), Page.class,
			() -> Page.decode(read(offset, index.lengths()[number])));
	}

	/* The manifest and the index say where blocks lie, and both may come from a damaged or forged blob */
	private byte[] read(final long offset, final long length) throws IOException, BlobStoreException {
		if (length < 0 || length > maxBlockBytes) {
			throw new CorruptIndexException("A run block claims " + length + " bytes");
		}

		final Blob blob = blobs.open(info.id());

		if (offset < 0 || offset > blob.size() - length) {
			throw new CorruptIndexException("A run block lies outside its run");
		}

		final ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) length);

		blob.writeTo(bytes, offset, length);
		return bytes.toByteArray();
	}

	private final class RunCursor implements Cursor {
		private final byte[] from;
		private final byte[] to;
		private RunIndex index;
		private Page page;
		private int number;
		private int position;

		RunCursor(final byte[] from, final byte[] to) {
			this.from = from;
			this.to = to;
		}

		@Override
		public Slot next() throws IOException, BlobStoreException {
			if (Objects.isNull(index)) {
				index = index();
				number = index.page(from);
				page = page(index, number);
				position = page.find(from);
			}

			while (position >= page.size()) {
				if (number + 1 >= index.pages()) {
					return null;
				}

				page = page(index, ++number);
				position = 0;
			}

			final Slot slot = page.slot(position++);
			return Keys.before(slot.key(), to) ? slot : null;
		}
	}
}
