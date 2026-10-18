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

import jakarta.ws.rs.container.ContainerRequestContext;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.http.Payload;
import org.unigrid.hedgehog.s3.index.BlobRef;
import org.unigrid.hedgehog.s3.index.NamespaceException;
import org.unigrid.hedgehog.s3.index.Part;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* A request body kept in a blob of its own, along with its MD5 and the checksum the client asked to keep. An empty
   body needs no blob. */
@Slf4j
record Body(List<BlobRef> slices, String etag, Map<String, String> checksums) {
	@FunctionalInterface
	interface Commit<T> {
		T run() throws IOException, BlobStoreException, NamespaceException;
	}

	static Body store(final BlobStore blobs, final ContainerRequestContext request, final long limit)
		throws IOException, BlobStoreException {

		final Payload payload = Payload.of(request);
		final long length = payload.getLength().orElseThrow(() -> new S3Exception(S3ErrorCode.MissingContentLength));

		if (length > limit) {
			throw new S3Exception(S3ErrorCode.EntityTooLarge);
		}

		final List<BlobRef> slices = length == 0 ? List.of()
			: List.of(new BlobRef(blobs.store(request.getEntityStream()), 0, length));

		try {
			payload.finish();
		} catch (IOException | RuntimeException ex) {
			new Body(slices, null, Map.of()).discard(blobs);
			throw ex;
		}

		return new Body(slices, payload.getETag(), payload.getAlgorithm().map(algorithm -> Map.of(algorithm.name(),
			payload.getChecksum().orElseThrow())).orElse(Map.of())
		);
	}

	/* Lets the index refer to the body, or deletes the body when the index refuses the write */
	<T> T commit(final BlobStore blobs, final Commit<T> commit) throws IOException, BlobStoreException,
		NamespaceException {

		try {
			return commit.run();
		} catch (S3Exception | NamespaceException ex) {
			discard(blobs);
			throw ex;
		}
	}

	Part part(final int number, final Instant at) {
		return Part.builder().number(number).slices(slices).etag(etag).checksums(checksums).lastModified(at).build();
	}

	List<Part> parts(final Instant at) {
		return slices.isEmpty() ? List.of() : List.of(part(1, at));
	}

	/* For a body nothing ever came to refer to, such as one whose write the index refused. Blob ids are never
	   handed out twice, so nothing else can be using it. */
	void discard(final BlobStore blobs) {
		for (BlobId blob : slices.stream().map(BlobRef::id).toList()) {
			try {
				blobs.delete(blob);
			} catch (IOException | BlobStoreException ex) {
				log.warn("Failed to delete the blob of a refused S3 write, which stays behind unused", ex);
			}
		}
	}
}
