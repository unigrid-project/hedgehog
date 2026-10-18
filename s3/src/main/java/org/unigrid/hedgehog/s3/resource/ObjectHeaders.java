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

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.ObjectVersion;

/* The headers an object keeps from the request that wrote it and answers every read with */
final class ObjectHeaders {
	private static final String METADATA = "x-amz-meta-";
	private static final String OVERRIDE = "response-";
	private static final String ENCRYPTION = "x-amz-server-side-encryption";
	private static final String STORAGE_CLASS = "x-amz-storage-class";
	private static final String STANDARD = "STANDARD";
	private static final String DEFAULT_TYPE = "binary/octet-stream";
	private static final String ACCEPT_RANGES = "Accept-Ranges";
	private static final String METADATA_DIRECTIVE = "x-amz-metadata-directive";
	private static final String COPY = "COPY";
	private static final String REPLACE = "REPLACE";

	private static final List<String> CONTENT = List.of(HttpHeaders.CONTENT_TYPE, "Content-Disposition",
		HttpHeaders.CONTENT_ENCODING, HttpHeaders.CONTENT_LANGUAGE, HttpHeaders.CACHE_CONTROL, HttpHeaders.EXPIRES
	);

	private ObjectHeaders() {
		/* Static helpers only */
	}

	static Map<String, String> content(final HttpHeaders headers) {
		return CONTENT.stream().filter(name -> Objects.nonNull(headers.getHeaderString(name)))
			.collect(Collectors.toMap(name -> name, headers::getHeaderString));
	}

	/* S3 keeps the names of user metadata in lower case */
	static Map<String, String> metadata(final HttpHeaders headers) {
		return headers.getRequestHeaders().keySet().stream().filter(name -> name.toLowerCase(Locale.ROOT)
			.startsWith(METADATA)).collect(Collectors.toMap(name -> name.substring(METADATA.length())
			.toLowerCase(Locale.ROOT), headers::getHeaderString, (first, second) -> first + "," + second));
	}

	/* Whether a copy takes its headers and metadata from the request instead of from its source */
	static boolean replaced(final HttpHeaders headers) {
		final String directive = Objects.requireNonNullElse(headers.getHeaderString(METADATA_DIRECTIVE), COPY);

		if (!COPY.equals(directive) && !REPLACE.equals(directive)) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Unknown metadata directive.");
		}

		return REPLACE.equals(directive);
	}

	/* Encryption is left to the storage layer and every object is stored alike, so asking for more is refused */
	static void refuseUnsupported(final HttpHeaders headers) {
		final boolean encryption = headers.getRequestHeaders().keySet().stream()
			.anyMatch(name -> name.toLowerCase(Locale.ROOT).startsWith(ENCRYPTION));
		final String storageClass = Objects.requireNonNullElse(headers.getHeaderString(STORAGE_CLASS), STANDARD);

		if (encryption || !STANDARD.equals(storageClass)) {
			throw new S3Exception(S3ErrorCode.NotImplemented, "Server-side encryption and storage classes other "
				+ "than STANDARD are not implemented."
			);
		}
	}

	/* What every read answers with, where a response-* query parameter replaces the content header it names */
	static Response.ResponseBuilder describe(final Response.ResponseBuilder response, final Bucket bucket,
		final ObjectVersion version, final MultivaluedMap<String, String> query) {

		final Map<String, String> content = new LinkedHashMap<>(version.headers());

		content.putIfAbsent(HttpHeaders.CONTENT_TYPE, DEFAULT_TYPE);
		CONTENT.forEach(name -> Optional.ofNullable(query.getFirst(OVERRIDE + name.toLowerCase(Locale.ROOT)))
			.ifPresent(value -> content.put(name, value))
		);

		content.forEach(response::header);
		version.metadata().forEach((name, value) -> response.header(METADATA + name, value));

		if (!version.tags().isEmpty()) {
			response.header(Tags.COUNT, version.tags().size());
		}

		return Responses.versioned(response.tag(new EntityTag(version.etag()))
			.lastModified(Date.from(version.lastModified())).header(ACCEPT_RANGES, "bytes"), bucket, version
		);
	}
}
