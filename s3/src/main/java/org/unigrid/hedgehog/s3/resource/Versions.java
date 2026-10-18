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

import jakarta.ws.rs.core.HttpHeaders;
import java.io.IOException;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

final class Versions {
	static final String VERSION_ID = "versionId";

	private Versions() {
		/* Static helpers only */
	}

	/* The version named, or the latest one without a version id, which may be a delete marker */
	static ObjectVersion find(final Namespace namespace, final String bucket, final String key,
		final String versionId) throws IOException, BlobStoreException {

		final Optional<ObjectVersion> found = Objects.isNull(versionId) ? namespace.latest(bucket, key)
			: namespace.version(bucket, key, versionId);

		if (found.isEmpty()) {
			Requests.bucket(namespace, bucket);
			throw new S3Exception(Objects.isNull(versionId) ? S3ErrorCode.NoSuchKey : S3ErrorCode.NoSuchVersion);
		}

		return found.get();
	}

	/* As S3 answers it, a delete marker hides its key, and is no object to read when named by its version id */
	static ObjectVersion readable(final Namespace namespace, final String bucket, final String key,
		final String versionId) throws IOException, BlobStoreException {

		final ObjectVersion version = find(namespace, bucket, key, versionId);

		if (version.deleteMarker()) {
			final S3Exception refusal = Objects.isNull(versionId) ? new S3Exception(S3ErrorCode.NoSuchKey)
				: new S3Exception(S3ErrorCode.MethodNotAllowed)
				.header(HttpHeaders.LAST_MODIFIED, Date.from(version.lastModified()));

			throw refusal.header(Responses.DELETE_MARKER, true)
				.header(Responses.VERSION_ID, version.versionId());
		}

		return version;
	}
}
