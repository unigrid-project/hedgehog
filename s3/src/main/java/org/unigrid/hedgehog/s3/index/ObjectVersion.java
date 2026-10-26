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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;
import org.unigrid.hedgehog.s3.spi.BlobId;

/* One version of an object, or a delete marker. The content is the concatenation of the slices of its parts: one
   part for a plain upload, the parts of a multipart upload otherwise. Checksums and headers map an algorithm or a
   header name to its value as it goes over the wire. Unversioned and suspended buckets keep a version whose id is
   "null", which a later unversioned write replaces. */
@Builder(toBuilder = true)
public record ObjectVersion(String bucket, String key, String versionId, boolean deleteMarker, Instant lastModified,
	String etag, List<Part> parts, Map<String, String> checksums, Map<String, String> headers,
	Map<String, String> metadata, Map<String, String> tags, Retention retention, boolean legalHold) {

	public static final String NULL_VERSION = "null";

	public ObjectVersion {
		Objects.requireNonNull(bucket);
		Objects.requireNonNull(key);
		Objects.requireNonNull(lastModified);
		parts = Immutable.list(parts);
		checksums = Immutable.map(checksums);
		headers = Immutable.map(headers);
		metadata = Immutable.map(metadata);
		tags = Immutable.map(tags);
	}

	public long size() {
		return parts.stream().mapToLong(Part::size).sum();
	}

	public boolean isNullVersion() {
		return NULL_VERSION.equals(versionId);
	}

	/* Whether Object Lock keeps the version from being removed at the instant */
	public boolean locked(final Instant at, final boolean bypassGovernance) {
		return legalHold || Objects.nonNull(retention) && retention.holds(at, bypassGovernance);
	}

	Set<BlobId> blobs() {
		return parts.stream().flatMap(part -> part.blobs().stream()).collect(Collectors.toSet());
	}
}
