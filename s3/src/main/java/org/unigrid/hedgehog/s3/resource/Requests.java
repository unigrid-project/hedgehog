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
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.entity.S3Xml;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.http.Payload;
import org.unigrid.hedgehog.s3.http.Subresource;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.request.ObjectKeys;
import org.unigrid.hedgehog.s3.request.Preconditions;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

final class Requests {
	static final int MAX_LISTED = 1000;

	private static final int MAX_XML_BYTES = 1 << 20;

	private Requests() {
		/* Static helpers only */
	}

	static Bucket bucket(final Namespace namespace, final String name) throws IOException, BlobStoreException {
		return namespace.bucket(name).orElseThrow(() -> new S3Exception(S3ErrorCode.NoSuchBucket));
	}

	/* Listings write milliseconds, so the index keeps no more than that */
	static Instant now(final Clock clock) {
		return clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

	/* How many entries a listing page may hold, where S3 quietly lowers a larger limit to its own */
	static int limit(final String value) {
		try {
			final int limit = Objects.isNull(value) ? MAX_LISTED : Integer.parseInt(value);

			if (limit >= 0) {
				return Math.min(limit, MAX_LISTED);
			}
		} catch (NumberFormatException ex) {
			/* Refused below, like a negative limit */
		}

		throw new S3Exception(S3ErrorCode.InvalidArgument, "A listing limit must be an integer of 0 or more.");
	}

	/* Every route that names an object passes its key through here before the index sees it */
	static String key(final String key) {
		if (Objects.isNull(key) || key.isEmpty()) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "The key must not be empty.");
		}

		if (!ObjectKeys.valid(key)) {
			throw new S3Exception(S3ErrorCode.KeyTooLongError);
		}

		return key;
	}

	/* Holds when the conditional headers of a write accept the latest version, and refuses the write otherwise.
	   Checked once before the body is stored and again as the guard of the index write. */
	static boolean writable(final HttpHeaders headers, final Optional<ObjectVersion> latest) {
		final Optional<String> current = latest.filter(version -> !version.deleteMarker()).map(ObjectVersion::etag);

		return switch (Preconditions.forWrite(headers.getHeaderString(HttpHeaders.IF_MATCH),
			headers.getHeaderString(HttpHeaders.IF_NONE_MATCH), current)) {

			case PROCEED -> true;
			case NOT_FOUND -> throw new S3Exception(S3ErrorCode.NoSuchKey);
			case PRECONDITION_FAILED -> throw new S3Exception(S3ErrorCode.PreconditionFailed);
		};
	}

	/* A subresource not served yet has to be refused, or the request would be taken for the plain operation */
	static Subresource subresource(final UriInfo uri, final Subresource... served) {
		final Subresource subresource = Subresource.of(uri.getQueryParameters());

		if (!Arrays.asList(served).contains(subresource)) {
			throw new S3Exception(S3ErrorCode.NotImplemented);
		}

		return subresource;
	}

	static <T> Optional<T> xml(final Class<T> type, final ContainerRequestContext request) throws IOException,
		MalformedXmlException {

		final byte[] body = body(request);

		return body.length == 0 ? Optional.empty()
			: Optional.of(S3Xml.read(type, new ByteArrayInputStream(body)));
	}

	/* The document of a request S3 has carry a digest of it, such as one that configures a bucket or locks a
	   version, as the text a bucket keeps */
	static String digestedXml(final ContainerRequestContext request) throws IOException {
		if (!digested(request)) {
			throw new S3Exception(S3ErrorCode.InvalidRequest, "Missing required header for this request: "
				+ Payload.CONTENT_MD5);
		}

		return new String(body(request), StandardCharsets.UTF_8);
	}

	static <T> T parse(final Class<T> type, final String document) throws IOException, MalformedXmlException {
		return S3Xml.read(type, new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
	}

	/* A configuration a bucket keeps was parsed before it was kept, so it parses again */
	@SneakyThrows({IOException.class, MalformedXmlException.class})
	static <T> T kept(final Class<T> type, final String document) {
		return parse(type, document);
	}

	/* Whether the body comes with Content-MD5 or a checksum, in a header or a trailer */
	static boolean digested(final ContainerRequestContext request) {
		return Objects.nonNull(request.getHeaderString(Payload.CONTENT_MD5))
			|| Payload.of(request).getAlgorithm().isPresent();
	}

	/* Request documents are small, so a body past the cap is refused rather than read on */
	private static byte[] body(final ContainerRequestContext request) throws IOException {
		final byte[] body = request.getEntityStream().readNBytes(MAX_XML_BYTES + 1);

		if (body.length > MAX_XML_BYTES) {
			throw new S3Exception(S3ErrorCode.MalformedXML, "The XML you provided was larger than "
				+ MAX_XML_BYTES + " bytes."
			);
		}

		Payload.of(request).finish();
		return body;
	}
}
