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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.http.Subresource;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceException;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.request.ByteRange;
import org.unigrid.hedgehog.s3.request.Outcome;
import org.unigrid.hedgehog.s3.request.Preconditions;
import org.unigrid.hedgehog.s3.request.UnsatisfiableRangeException;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

@Path("/{bucket}/{key:.+}")
@RequiredArgsConstructor
public class ObjectResource {
	/* The largest object a single PUT may write, and the largest part of a multipart upload */
	static final long MAX_SIZE = 5L << 30;

	private static final String RANGE = "Range";
	private static final String CONTENT_RANGE = "Content-Range";
	private static final String CHECKSUM_MODE = "x-amz-checksum-mode";
	private static final String ENABLED = "ENABLED";

	private final Namespace namespace;
	private final BlobStore blobs;
	private final Clock clock;

	@PUT
	public Response put(@PathParam("bucket") final String bucket, @PathParam("key") final String key,
		@Context final UriInfo uri, @Context final HttpHeaders headers,
		@Context final ContainerRequestContext request) throws IOException, BlobStoreException, NamespaceException {

		Requests.subresource(uri, Subresource.NONE);
		return putObject(bucket, key, headers, request);
	}

	@GET
	public Response get(@PathParam("bucket") final String bucket, @PathParam("key") final String key,
		@Context final UriInfo uri, @Context final HttpHeaders headers) throws IOException, BlobStoreException {

		Requests.subresource(uri, Subresource.NONE);
		return read(bucket, key, uri.getQueryParameters(), headers, true);
	}

	@HEAD
	public Response head(@PathParam("bucket") final String bucket, @PathParam("key") final String key,
		@Context final UriInfo uri, @Context final HttpHeaders headers) throws IOException, BlobStoreException {

		Requests.subresource(uri, Subresource.NONE);
		return read(bucket, key, uri.getQueryParameters(), headers, false);
	}

	private Response putObject(final String bucket, final String key, final HttpHeaders headers,
		final ContainerRequestContext request) throws IOException, BlobStoreException, NamespaceException {

		Requests.key(key);
		ObjectHeaders.refuseUnsupported(headers);
		Requests.bucket(namespace, bucket);
		Requests.writable(headers, namespace.latest(bucket, key));

		final Instant now = Requests.now(clock);
		final Body body = Body.store(blobs, request, MAX_SIZE);
		final ObjectVersion stored = body.commit(blobs, () -> namespace.put(ObjectVersion.builder().bucket(bucket)
			.key(key).lastModified(now).etag(body.etag()).parts(body.parts(now)).checksums(body.checksums())
			.headers(ObjectHeaders.content(headers)).metadata(ObjectHeaders.metadata(headers)).build(),
			latest -> Requests.writable(headers, latest))
		);

		return Responses.checksums(Responses.versioned(Response.ok().tag(new EntityTag(body.etag())), stored),
			body.checksums()).build();
	}

	private Response read(final String bucket, final String key, final MultivaluedMap<String, String> query,
		final HttpHeaders headers, final boolean withBody) throws IOException, BlobStoreException {

		final ObjectVersion version = Versions.readable(namespace, bucket, key, query.getFirst(Versions.VERSION_ID));
		final long size = version.size();

		checkRead(headers, version);

		final Optional<ByteRange> range = range(headers.getHeaderString(RANGE), size);
		final long offset = range.map(ByteRange::first).orElse(0L);
		final long length = range.map(ByteRange::length).orElse(size);
		final Response.ResponseBuilder response = ObjectHeaders.describe(Response.status(range.isPresent()
			? Response.Status.PARTIAL_CONTENT : Response.Status.OK), version, query)
			.header(HttpHeaders.CONTENT_LENGTH, length);

		range.ifPresent(within -> response.header(CONTENT_RANGE, "bytes " + within.first() + "-" + within.last()
			+ "/" + size
		));

		/* A checksum covers the whole object, so a range is answered without one */
		if (range.isEmpty() && ENABLED.equalsIgnoreCase(headers.getHeaderString(CHECKSUM_MODE))) {
			Responses.checksums(response, version.checksums());
		}

		return withBody ? response.entity(Content.of(blobs, version.parts(), offset, length)).build()
			: response.build();
	}

	private static void checkRead(final HttpHeaders headers, final ObjectVersion version) {
		final Outcome outcome = Preconditions.forRead(headers.getHeaderString(HttpHeaders.IF_MATCH),
			headers.getHeaderString(HttpHeaders.IF_NONE_MATCH),
			headers.getHeaderString(HttpHeaders.IF_MODIFIED_SINCE),
			headers.getHeaderString(HttpHeaders.IF_UNMODIFIED_SINCE), version.etag(), version.lastModified()
		);

		if (outcome == Outcome.PRECONDITION_FAILED) {
			throw new S3Exception(S3ErrorCode.PreconditionFailed);
		} else if (outcome == Outcome.NOT_MODIFIED) {
			throw new S3Exception(S3ErrorCode.NotModified)
				.header(HttpHeaders.ETAG, new EntityTag(version.etag()))
				.header(HttpHeaders.LAST_MODIFIED, Date.from(version.lastModified()));
		}
	}

	/* A range that does not parse is ignored and the whole object served, as S3 does */
	private static Optional<ByteRange> range(final String header, final long size) {
		try {
			return ByteRange.parse(header, size);
		} catch (UnsatisfiableRangeException ex) {
			throw new S3Exception(S3ErrorCode.InvalidRange).header(CONTENT_RANGE, "bytes */" + size);
		}
	}
}
