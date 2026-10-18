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

package org.unigrid.hedgehog.s3.resource;

import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.index.BlobRef;
import org.unigrid.hedgehog.s3.index.Part;
import org.unigrid.hedgehog.s3.spi.Blob;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* The bytes of an object within a range, read from the slices of its parts in turn. The first blob is opened before
   the response starts, so a store that cannot serve it still has its refusal answered as an error. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class Content implements StreamingOutput {
	private final BlobStore blobs;
	private final List<BlobRef> slices;
	private final Blob first;

	static Content of(final BlobStore blobs, final List<Part> parts, final long offset, final long length)
		throws IOException, BlobStoreException {

		final List<BlobRef> slices = slice(parts, offset, length);
		return new Content(blobs, slices, slices.isEmpty() ? null : blobs.open(slices.getFirst().id()));
	}

	/* The slices of blobs that hold the bytes of the parts within the range, in order */
	static List<BlobRef> slice(final List<Part> parts, final long offset, final long length) {
		final List<BlobRef> within = new ArrayList<>();
		long start = 0;

		for (BlobRef slice : parts.stream().flatMap(part -> part.slices().stream()).toList()) {
			final long from = Math.max(offset, start);
			final long to = Math.min(offset + length, start + slice.length());

			if (from < to) {
				within.add(new BlobRef(slice.id(), slice.offset() + from - start, to - from));
			}

			start += slice.length();
		}

		return within;
	}

	@Override
	public void write(final OutputStream output) throws IOException {
		try {
			for (int i = 0; i < slices.size(); i++) {
				final BlobRef slice = slices.get(i);
				final Blob blob = i == 0 ? first : blobs.open(slice.id());

				blob.writeTo(output, slice.offset(), slice.length());
			}
		} catch (BlobStoreException ex) {
			throw new IOException("Failed to read a blob of the object", ex);
		}
	}
}
