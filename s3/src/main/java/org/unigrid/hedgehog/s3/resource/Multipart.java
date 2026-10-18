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
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.s3.entity.CompleteMultipartUpload;
import org.unigrid.hedgehog.s3.entity.CompleteMultipartUploadResult;
import org.unigrid.hedgehog.s3.entity.CopyPartResult;
import org.unigrid.hedgehog.s3.entity.InitiateMultipartUploadResult;
import org.unigrid.hedgehog.s3.entity.ListPartsResult;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.Listing;
import org.unigrid.hedgehog.s3.index.Marker;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceException;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.index.Part;
import org.unigrid.hedgehog.s3.index.Upload;
import org.unigrid.hedgehog.s3.request.ByteRange;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Multipart uploads, where each part is a blob of its own, or slices of another object for a part copied from it,
   and completing an upload strings the parts together without copying any data */
@RequiredArgsConstructor
final class Multipart {
	private static final String UPLOAD_ID = "uploadId";
	private static final String PART_NUMBER = "partNumber";
	private static final String CHECKSUM_ALGORITHM = "x-amz-checksum-algorithm";
	private static final String CHECKSUM_TYPE = "x-amz-checksum-type";
	private static final String COPY_RANGE = "x-amz-copy-source-range";
	private static final String STANDARD = "STANDARD";
	private static final int MAX_PARTS = 10_000;
	private static final long MIN_PART_SIZE = 5L << 20;
	private static final Pattern RANGE = Pattern.compile("bytes=(\\d{1,18})-(\\d{1,18})");
	private static final Pattern MD5 = Pattern.compile("[0-9a-f]{32}");

	private final Namespace namespace;
	private final BlobStore blobs;
	private final Clock clock;

	Response create(final String bucket, final String key, final HttpHeaders headers) throws IOException,
		BlobStoreException, NamespaceException {

		ObjectHeaders.refuseUnsupported(headers);

		final Instant now = Requests.now(clock);
		final ObjectVersion template = ObjectLocks.lock(ObjectVersion.builder().bucket(bucket)
			.key(Requests.key(key)).lastModified(now).headers(ObjectHeaders.content(headers))
			.metadata(ObjectHeaders.metadata(headers)).tags(Tags.header(headers)), Requests.bucket(namespace,
			bucket), headers, now).build();
		final Upload upload = namespace.createUpload(Upload.builder().template(template)
			.checksumAlgorithm(headers.getHeaderString(CHECKSUM_ALGORITHM))
			.checksumType(headers.getHeaderString(CHECKSUM_TYPE)).build()
		);
		final Response.ResponseBuilder response = Response.ok(new InitiateMultipartUploadResult(bucket, key,
			upload.uploadId()), MediaType.APPLICATION_XML_TYPE);

		Optional.ofNullable(upload.checksumAlgorithm()).ifPresent(name -> response.header(CHECKSUM_ALGORITHM, name));
		return response.build();
	}

	Response uploadPart(final String bucket, final String key, final MultivaluedMap<String, String> query,
		final ContainerRequestContext request) throws IOException, BlobStoreException, NamespaceException {

		final int number = partNumber(query);
		final String uploadId = upload(bucket, key, query).uploadId();
		final Instant now = Requests.now(clock);
		final Body body = Body.store(blobs, request, ObjectResource.MAX_SIZE);

		body.commit(blobs, () -> namespace.putPart(bucket, key, uploadId, body.part(number, now)));
		return Responses.checksums(Response.ok().tag(new EntityTag(body.etag())), body.checksums()).build();
	}

	/* The part refers to slices of the source instead of copying them. Its ETag cannot be the MD5 of data it
	   never reads, so unless the part is the whole of an object with an MD5 ETag, it is the MD5 of where the
	   part was copied from, which still tells parts apart wherever their ETags are compared. */
	Response copyPart(final String bucket, final String key, final MultivaluedMap<String, String> query,
		final HttpHeaders headers, final CopySource source) throws IOException, BlobStoreException,
		NamespaceException {

		final int number = partNumber(query);
		final String uploadId = upload(bucket, key, query).uploadId();
		final ObjectVersion from = source.read(namespace, headers);
		final Instant now = Requests.now(clock);
		final Part part = namespace.copyPart(bucket, key, uploadId, source.pinned(from), found -> copiedPart(
			source.check(found, headers), headers.getHeaderString(COPY_RANGE), number, now));
		final CopyPartResult result = new CopyPartResult();

		result.setETag(Responses.quoted(part.etag()));
		result.setLastModified(now);
		return Responses.copied(Response.ok(result, MediaType.APPLICATION_XML_TYPE), from).build();
	}

	private static Part copiedPart(final ObjectVersion from, final String rangeHeader, final int number,
		final Instant now) {

		final ByteRange range = copyRange(rangeHeader, from.size());
		final boolean whole = range.length() == from.size() && MD5.matcher(from.etag()).matches();
		final String etag = whole ? from.etag() : md5(from.etag() + " " + range.first() + "-" + range.last());

		return Part.builder().number(number).etag(etag).lastModified(now)
			.slices(Content.slice(from.parts(), range.first(), range.length())).build();
	}

	Response complete(final String bucket, final String key, final UriInfo uri, final HttpHeaders headers,
		final ContainerRequestContext request) throws IOException, BlobStoreException, NamespaceException,
		MalformedXmlException {

		final Bucket found = Requests.bucket(namespace, bucket);
		final List<org.unigrid.hedgehog.s3.entity.Part> requested = requested(Requests.xml(
			CompleteMultipartUpload.class, request));
		final ObjectVersion stored = namespace.completeUpload(bucket, Requests.key(key),
			uri.getQueryParameters().getFirst(UPLOAD_ID), (upload, parts) -> assemble(upload, parts, requested),
			latest -> Requests.writable(headers, latest)
		);

		final CompleteMultipartUploadResult result = new CompleteMultipartUploadResult();

		result.setLocation(uri.getAbsolutePath().toString());
		result.setBucket(bucket);
		result.setKey(key);
		result.setETag(Responses.quoted(stored.etag()));
		return Responses.versioned(Response.ok(result, MediaType.APPLICATION_XML_TYPE), found, stored).build();
	}

	Response abort(final String bucket, final String key, final MultivaluedMap<String, String> query)
		throws IOException, BlobStoreException, NamespaceException {

		namespace.abortUpload(bucket, Requests.key(key), query.getFirst(UPLOAD_ID));
		return Response.noContent().build();
	}

	Response listParts(final String bucket, final String key, final MultivaluedMap<String, String> query)
		throws IOException, BlobStoreException, NamespaceException {

		final Upload upload = upload(bucket, key, query);
		final int marker = number(Objects.requireNonNullElse(query.getFirst("part-number-marker"), "0"));
		final int limit = Requests.limit(query.getFirst("max-parts"));
		final Listing<Part> listing = namespace.parts(bucket, key, upload.uploadId(), marker, limit);
		final ListPartsResult result = new ListPartsResult();

		result.setBucket(bucket);
		result.setKey(key);
		result.setUploadId(upload.uploadId());
		result.setPartNumberMarker(marker);
		result.setNextPartNumberMarker(listing.next().map(Marker::id).map(Integer::valueOf).orElse(null));
		result.setMaxParts(limit);
		result.setIsTruncated(listing.truncated());
		result.setParts(listing.entries().stream().map(Multipart::describe).toList());
		result.setInitiator(Responses.owner());
		result.setOwner(Responses.owner());
		result.setStorageClass(STANDARD);
		result.setChecksumAlgorithm(upload.checksumAlgorithm());
		return Responses.xml(result);
	}

	private static org.unigrid.hedgehog.s3.entity.Part describe(final Part part) {
		final org.unigrid.hedgehog.s3.entity.Part described = new org.unigrid.hedgehog.s3.entity.Part();

		described.setPartNumber(part.number());
		described.setLastModified(part.lastModified());
		described.setETag(Responses.quoted(part.etag()));
		described.setSize(part.size());
		return described;
	}

	private Upload upload(final String bucket, final String key, final MultivaluedMap<String, String> query)
		throws IOException, BlobStoreException {

		return namespace.upload(bucket, Requests.key(key), query.getFirst(UPLOAD_ID))
			.orElseThrow(() -> new S3Exception(S3ErrorCode.NoSuchUpload));
	}

	private static int partNumber(final MultivaluedMap<String, String> query) {
		final int number = number(query.getFirst(PART_NUMBER));

		if (number < 1 || number > MAX_PARTS) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Part number must be an integer between 1 and "
				+ MAX_PARTS + ", inclusive"
			);
		}

		return number;
	}

	private static int number(final String value) {
		try {
			return Integer.parseInt(Objects.requireNonNullElse(value, ""));
		} catch (NumberFormatException ex) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "A part number must be an integer.");
		}
	}

	/* The whole source when no range is given, where an empty source is a range of no bytes */
	private static ByteRange copyRange(final String header, final long size) {
		if (Objects.isNull(header)) {
			return new ByteRange(0, size - 1);
		}

		final Matcher range = RANGE.matcher(header);
		final boolean valid = range.matches() && Long.parseLong(range.group(1)) <= Long.parseLong(range.group(2))
			&& Long.parseLong(range.group(2)) < size;

		if (!valid) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Range specified is not valid for source object "
				+ "of size: " + size
			);
		}

		return new ByteRange(Long.parseLong(range.group(1)), Long.parseLong(range.group(2)));
	}

	private static List<org.unigrid.hedgehog.s3.entity.Part> requested(
		final Optional<CompleteMultipartUpload> document) {

		final List<org.unigrid.hedgehog.s3.entity.Part> parts = document.map(CompleteMultipartUpload::getParts)
			.orElse(List.of());

		if (parts.isEmpty() || parts.stream().anyMatch(part -> Objects.isNull(part.getPartNumber()))) {
			throw new S3Exception(S3ErrorCode.MalformedXML);
		}

		for (int i = 1; i < parts.size(); i++) {
			if (parts.get(i).getPartNumber() <= parts.get(i - 1).getPartNumber()) {
				throw new S3Exception(S3ErrorCode.InvalidPartOrder);
			}
		}

		return parts;
	}

	/* Strings together the parts the client lists, whose ETags have to be those the parts were stored with. The
	   ETag of the whole is the MD5 of the MD5s of its parts and their count, as S3 makes it. */
	@SneakyThrows
	private static ObjectVersion assemble(final Upload upload, final List<Part> stored,
		final List<org.unigrid.hedgehog.s3.entity.Part> requested) {

		final Map<Integer, Part> numbered = stored.stream().collect(Collectors.toMap(Part::number,
			Function.identity()));
		final MessageDigest md5 = MessageDigest.getInstance("MD5");
		final List<Part> parts = new ArrayList<>();

		for (org.unigrid.hedgehog.s3.entity.Part wanted : requested) {
			final Part part = Optional.ofNullable(numbered.get(wanted.getPartNumber()))
				.filter(found -> found.etag().equals(unquoted(wanted.getETag())))
				.orElseThrow(() -> new S3Exception(S3ErrorCode.InvalidPart));

			if (!parts.isEmpty() && parts.getLast().size() < MIN_PART_SIZE) {
				throw new S3Exception(S3ErrorCode.EntityTooSmall);
			}

			parts.add(part);
			md5.update(HexFormat.of().parseHex(part.etag()));
		}

		return upload.template().toBuilder().parts(parts).etag(HexFormat.of().formatHex(md5.digest()) + "-"
			+ parts.size()).build();
	}

	private static String unquoted(final String etag) {
		return Objects.requireNonNullElse(etag, "").replace("\"", "");
	}

	@SneakyThrows
	private static String md5(final String text) {
		return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
			.digest(text.getBytes(StandardCharsets.UTF_8)));
	}
}
