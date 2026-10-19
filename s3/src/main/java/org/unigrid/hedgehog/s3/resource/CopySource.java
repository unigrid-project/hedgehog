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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.index.VersionRef;
import org.unigrid.hedgehog.s3.request.Outcome;
import org.unigrid.hedgehog.s3.request.Preconditions;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* The object a copy reads, as x-amz-copy-source names it: URL-encoded, with or without a leading slash, and
   optionally followed by the version to read */
record CopySource(String bucket, String key, String versionId) {
	static final String HEADER = "x-amz-copy-source";
	static final String VERSION_HEADER = "x-amz-copy-source-version-id";

	private static final String CONDITION = HEADER + "-";
	private static final String VERSION_PARAMETER = Versions.VERSION_ID + "=";

	static Optional<CopySource> of(final HttpHeaders headers) {
		return Optional.ofNullable(headers.getHeaderString(HEADER)).map(CopySource::parse);
	}

	static CopySource parse(final String header) {
		final int query = header.indexOf('?');
		final String path = decode(query < 0 ? header : header.substring(0, query)).replaceFirst("^/", "");
		final int slash = path.indexOf('/');

		if (slash <= 0 || slash == path.length() - 1) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Copy Source must mention the source bucket and "
				+ "key: sourcebucket/sourcekey"
			);
		}

		return new CopySource(path.substring(0, slash), path.substring(slash + 1),
			query < 0 ? null : versionId(header.substring(query + 1)));
	}

	private static String versionId(final String query) {
		return Arrays.stream(query.split("&")).filter(parameter -> parameter.startsWith(VERSION_PARAMETER))
			.map(parameter -> decode(parameter.substring(VERSION_PARAMETER.length()))).findFirst().orElse(null);
	}

	/* Percent-decoding only, since a plus sign in a key stands for itself */
	private static String decode(final String encoded) {
		try {
			return URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ex) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Invalid copy source encoding");
		}
	}

	boolean names(final String otherBucket, final String otherKey) {
		return bucket.equals(otherBucket) && key.equals(otherKey) && Objects.isNull(versionId);
	}

	/* The version to copy as a snapshot shows it, which answers a request that cannot succeed before it writes */
	ObjectVersion read(final Namespace namespace, final HttpHeaders headers) throws IOException, BlobStoreException {
		return check(Optional.of(Versions.find(namespace, bucket, key, versionId)), headers);
	}

	/* The version read names, which the copy reads once more along with its write */
	VersionRef pinned(final ObjectVersion read) {
		return new VersionRef(bucket, key, read.versionId());
	}

	/* The version found, once the copy-source conditions accept it. A delete marker hides its key here as well, and
	   naming one by its version id is refused. */
	ObjectVersion check(final Optional<ObjectVersion> found, final HttpHeaders headers) {
		final ObjectVersion version = found.orElseThrow(() -> new S3Exception(Objects.isNull(versionId)
			? S3ErrorCode.NoSuchKey : S3ErrorCode.NoSuchVersion));

		if (version.deleteMarker()) {
			throw Objects.isNull(versionId) ? new S3Exception(S3ErrorCode.NoSuchKey)
				: new S3Exception(S3ErrorCode.InvalidRequest, "The source of a copy request may not "
				+ "specifically refer to a delete marker by version id.");
		}

		if (Preconditions.forCopySource(headers.getHeaderString(CONDITION + "if-match"),
			headers.getHeaderString(CONDITION + "if-none-match"),
			headers.getHeaderString(CONDITION + "if-modified-since"),
			headers.getHeaderString(CONDITION + "if-unmodified-since"), version.etag(), version.lastModified())
			!= Outcome.PROCEED) {

			throw new S3Exception(S3ErrorCode.PreconditionFailed);
		}

		return version;
	}
}
