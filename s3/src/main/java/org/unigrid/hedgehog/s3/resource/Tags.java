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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.entity.S3Xml;
import org.unigrid.hedgehog.s3.entity.Tag;
import org.unigrid.hedgehog.s3.entity.Tagging;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceException;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* The tag set of an object version or a bucket, in the document form a tagging route carries and the query-encoded
   form x-amz-tagging carries, along with what S3 accepts of either */
final class Tags {
	static final String HEADER = "x-amz-tagging";
	static final String COUNT = "x-amz-tagging-count";

	private static final String DIRECTIVE = "x-amz-tagging-directive";
	private static final String COPY = "COPY";
	private static final String REPLACE = "REPLACE";
	private static final String RESERVED = "aws:";
	private static final int MAX_OBJECT_TAGS = 10;
	private static final int MAX_BUCKET_TAGS = 50;
	private static final int MAX_KEY_LENGTH = 128;
	private static final int MAX_VALUE_LENGTH = 256;

	private Tags() {
		/* Static helpers only */
	}

	static Response readObject(final Namespace namespace, final String bucket, final String key,
		final MultivaluedMap<String, String> query) throws IOException, BlobStoreException {

		final ObjectVersion version = Versions.readable(namespace, bucket, key, query.getFirst(Versions.VERSION_ID));

		return Responses.versioned(Response.ok(document(version.tags()), MediaType.APPLICATION_XML_TYPE), version)
			.build();
	}

	static Response writeObject(final Namespace namespace, final String bucket, final String key,
		final MultivaluedMap<String, String> query, final ContainerRequestContext request) throws IOException,
		BlobStoreException, MalformedXmlException {

		final ObjectVersion version = Versions.readable(namespace, bucket, key, query.getFirst(Versions.VERSION_ID));
		final Map<String, String> tags = read(request, MAX_OBJECT_TAGS);
		final ObjectVersion stored = namespace.update(bucket, key, version.versionId(),
			current -> current.toBuilder().tags(tags).build())
			.orElseThrow(() -> new S3Exception(S3ErrorCode.NoSuchKey));

		return Responses.versioned(Response.ok(), stored).build();
	}

	static Response deleteObject(final Namespace namespace, final String bucket, final String key,
		final MultivaluedMap<String, String> query) throws IOException, BlobStoreException {

		final ObjectVersion version = Versions.readable(namespace, bucket, key, query.getFirst(Versions.VERSION_ID));
		final ObjectVersion stored = namespace.update(bucket, key, version.versionId(),
			current -> current.toBuilder().tags(Map.of()).build())
			.orElseThrow(() -> new S3Exception(S3ErrorCode.NoSuchKey));

		return Responses.versioned(Response.noContent(), stored).build();
	}

	/* A bucket without tags has no tag set to answer with, which S3 reports as an error of its own */
	static Tagging bucket(final Bucket bucket) {
		if (bucket.tags().isEmpty()) {
			throw new S3Exception(S3ErrorCode.NoSuchTagSet);
		}

		return document(bucket.tags());
	}

	static Response writeBucket(final Namespace namespace, final String name, final ContainerRequestContext request)
		throws IOException, BlobStoreException, NamespaceException, MalformedXmlException {

		Requests.bucket(namespace, name);

		final Map<String, String> tags = read(request, MAX_BUCKET_TAGS);

		namespace.updateBucket(name, bucket -> bucket.toBuilder().tags(tags).build());
		return Response.noContent().build();
	}

	static Response deleteBucket(final Namespace namespace, final String name) throws IOException, BlobStoreException,
		NamespaceException {

		Requests.bucket(namespace, name);
		namespace.updateBucket(name, bucket -> bucket.toBuilder().tags(Map.of()).build());
		return Response.noContent().build();
	}

	/* The tags PutObject, CreateMultipartUpload and a replacing copy keep from x-amz-tagging */
	static Map<String, String> header(final HttpHeaders headers) {
		return fromHeader(headers.getHeaderString(HEADER));
	}

	/* The tags a browser form upload keeps from its tagging field, which holds the document form */
	static Map<String, String> field(final String document) throws IOException, MalformedXmlException {
		return validate(collected(S3Xml.read(Tagging.class, new ByteArrayInputStream(document
			.getBytes(StandardCharsets.UTF_8)))), MAX_OBJECT_TAGS
		);
	}

	static boolean replaced(final HttpHeaders headers) {
		final String directive = Objects.requireNonNullElse(headers.getHeaderString(DIRECTIVE), COPY);

		if (!COPY.equals(directive) && !REPLACE.equals(directive)) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Unknown tagging directive.");
		}

		return REPLACE.equals(directive);
	}

	static Tagging document(final Map<String, String> tags) {
		return new Tagging(tags.entrySet().stream().map(tag -> new Tag(tag.getKey(), tag.getValue())).toList());
	}

	/* Unlike the document form, the header carries its tag set as URL query parameters */
	private static Map<String, String> fromHeader(final String encoded) {
		if (Objects.isNull(encoded) || encoded.isEmpty()) {
			return Map.of();
		}

		final Map<String, String> tags = new LinkedHashMap<>();

		for (String pair : encoded.split("&")) {
			final int separator = pair.indexOf('=');

			if (separator < 0) {
				throw invalid("A tag in the header must be key=value.");
			}

			final String key = decode(pair.substring(0, separator));
			final String value = decode(pair.substring(separator + 1));

			if (tags.containsKey(key)) {
				throw invalid("Tag keys must be unique.");
			}

			tags.put(key, value);
		}

		return validate(tags, MAX_OBJECT_TAGS);
	}

	private static String decode(final String encoded) {
		try {
			return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ex) {
			throw invalid("A tag in the header is not URL encoded.");
		}
	}

	private static Map<String, String> read(final ContainerRequestContext request, final int limit)
		throws IOException, MalformedXmlException {

		final Tagging tagging = Requests.xml(Tagging.class, request)
			.orElseThrow(() -> new S3Exception(S3ErrorCode.MalformedXML));

		return validate(collected(tagging), limit);
	}

	private static Map<String, String> collected(final Tagging tagging) {
		final Map<String, String> tags = new LinkedHashMap<>();

		for (Tag tag : tagging.getTags()) {
			if (tags.containsKey(tag.getKey())) {
				throw invalid("Tag keys must be unique.");
			}

			tags.put(tag.getKey(), tag.getValue());
		}

		return tags;
	}

	private static Map<String, String> validate(final Map<String, String> tags, final int limit) {
		if (tags.size() > limit) {
			throw invalid("A tag set may hold at most " + limit + " tags.");
		}

		tags.forEach(Tags::valid);
		return tags;
	}

	private static void valid(final String key, final String value) {
		validKey(key);
		validValue(value);
	}

	private static void validKey(final String key) {
		if (Objects.isNull(key) || key.isEmpty() || length(key) > MAX_KEY_LENGTH) {
			throw invalid("Tag keys must be 1 to " + MAX_KEY_LENGTH + " Unicode characters.");
		}

		if (key.startsWith(RESERVED)) {
			throw invalid("Tag keys must not start with " + RESERVED);
		}
	}

	private static void validValue(final String value) {
		if (Objects.isNull(value) || length(value) > MAX_VALUE_LENGTH) {
			throw invalid("Tag values must be at most " + MAX_VALUE_LENGTH + " Unicode characters.");
		}
	}

	private static int length(final String value) {
		return value.codePointCount(0, value.length());
	}

	private static S3Exception invalid(final String message) {
		return new S3Exception(S3ErrorCode.InvalidTag, message);
	}
}
