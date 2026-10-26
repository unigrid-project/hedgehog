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

import jakarta.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.entity.CommonPrefix;
import org.unigrid.hedgehog.s3.entity.DeleteMarker;
import org.unigrid.hedgehog.s3.entity.ListBucketResult;
import org.unigrid.hedgehog.s3.entity.ListBucketResultV2;
import org.unigrid.hedgehog.s3.entity.ListMultipartUploadsResult;
import org.unigrid.hedgehog.s3.entity.ListVersionsResult;
import org.unigrid.hedgehog.s3.entity.ObjectSummary;
import org.unigrid.hedgehog.s3.entity.Version;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.http.Subresource;
import org.unigrid.hedgehog.s3.index.ContinuationToken;
import org.unigrid.hedgehog.s3.index.ListQuery;
import org.unigrid.hedgehog.s3.index.Listing;
import org.unigrid.hedgehog.s3.index.Marker;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.index.Upload;
import org.unigrid.hedgehog.s3.index.VersionEntry;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* The listings of the keys in a bucket. With encoding-type=url, every key, prefix, delimiter and marker the
   answer holds is URL-encoded, which lets it carry characters XML 1.0 cannot. */
@RequiredArgsConstructor
final class Listings {
	static final String PREFIX = "prefix";
	static final String DELIMITER = "delimiter";
	static final String ENCODING_TYPE = "encoding-type";

	private static final String URL = "url";
	private static final String STANDARD = "STANDARD";
	private static final String VERSION_2 = "2";

	private final Namespace namespace;

	static UnaryOperator<String> encoding(final String type) {
		if (Objects.isNull(type)) {
			return UnaryOperator.identity();
		} else if (!URL.equals(type)) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Invalid Encoding Method specified in Request");
		}

		return value -> Objects.isNull(value) ? null
			: URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%2F", "/");
	}

	static ListQuery query(final MultivaluedMap<String, String> query, final String marker, final int limit) {
		return ListQuery.builder().prefix(query.getFirst(PREFIX)).delimiter(query.getFirst(DELIMITER))
			.keyMarker(marker).limit(limit).build();
	}

	static List<CommonPrefix> prefixes(final Listing<?> listing, final UnaryOperator<String> encode) {
		return listing.commonPrefixes().stream().map(prefix -> new CommonPrefix(encode.apply(prefix))).toList();
	}

	/* ListObjects, whose next page starts after the key or common prefix this one ended on */
	ListBucketResult objects(final String bucket, final MultivaluedMap<String, String> query) throws IOException,
		BlobStoreException {

		final UnaryOperator<String> encode = encoding(query.getFirst(ENCODING_TYPE));
		final String marker = Objects.requireNonNullElse(query.getFirst("marker"), "");
		final ListQuery listed = query(query, marker, Requests.limit(query.getFirst("max-keys")));
		final Listing<ObjectVersion> listing = namespace.listObjects(bucket, listed);
		final ListBucketResult result = new ListBucketResult();

		result.setName(bucket);
		result.setPrefix(encode.apply(listed.prefix()));
		result.setMarker(encode.apply(marker));
		result.setNextMarker(listing.next().map(Marker::key).map(encode).orElse(null));
		result.setMaxKeys(listed.limit());
		result.setDelimiter(encode.apply(query.getFirst(DELIMITER)));
		result.setIsTruncated(listing.truncated());
		result.setEncodingType(query.getFirst(ENCODING_TYPE));
		result.setContents(summaries(listing, true, encode));
		result.setCommonPrefixes(prefixes(listing, encode));
		return result;
	}

	/* ListObjectsV2, whose continuation token names where the next page starts, and which leaves out the owner of
	   each object unless asked */
	ListBucketResultV2 objectsV2(final String bucket, final MultivaluedMap<String, String> query)
		throws IOException, BlobStoreException {

		if (!VERSION_2.equals(query.getFirst(Subresource.LIST_TYPE.getParameter()))) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Invalid List Type specified in Request");
		}

		final UnaryOperator<String> encode = encoding(query.getFirst(ENCODING_TYPE));
		final String token = query.getFirst("continuation-token");
		final ListQuery listed = query(query, Objects.isNull(token) ? query.getFirst("start-after") : resume(token),
			Requests.limit(query.getFirst("max-keys")));
		final Listing<ObjectVersion> listing = namespace.listObjects(bucket, listed);
		final ListBucketResultV2 result = new ListBucketResultV2();

		result.setName(bucket);
		result.setPrefix(encode.apply(listed.prefix()));
		result.setMaxKeys(listed.limit());
		result.setDelimiter(encode.apply(query.getFirst(DELIMITER)));
		result.setIsTruncated(listing.truncated());
		result.setEncodingType(query.getFirst(ENCODING_TYPE));
		result.setKeyCount(listing.entries().size() + listing.commonPrefixes().size());
		result.setContinuationToken(token);
		result.setNextContinuationToken(listing.next().map(Marker::key).map(ContinuationToken::encode)
			.orElse(null));
		result.setStartAfter(encode.apply(query.getFirst("start-after")));
		result.setContents(summaries(listing, Boolean.parseBoolean(query.getFirst("fetch-owner")), encode));
		result.setCommonPrefixes(prefixes(listing, encode));
		return result;
	}

	/* ListObjectVersions, where the versions and delete markers of one key run from the newest, and a page may end
	   inside them */
	ListVersionsResult versions(final String bucket, final MultivaluedMap<String, String> query)
		throws IOException, BlobStoreException {

		final UnaryOperator<String> encode = encoding(query.getFirst(ENCODING_TYPE));
		final String keyMarker = Objects.requireNonNullElse(query.getFirst("key-marker"), "");
		final String idMarker = Objects.requireNonNullElse(query.getFirst("version-id-marker"), "");

		if (keyMarker.isEmpty() && !idMarker.isEmpty()) {
			throw new S3Exception(S3ErrorCode.InvalidArgument,
				"A version-id marker cannot be specified without a key marker.");
		}

		final ListQuery listed = query(query, keyMarker, Requests.limit(query.getFirst("max-keys"))).toBuilder()
			.idMarker(idMarker).build();
		final Listing<VersionEntry> listing = namespace.listVersions(bucket, listed);
		final ListVersionsResult result = new ListVersionsResult();

		result.setName(bucket);
		result.setPrefix(encode.apply(listed.prefix()));
		result.setKeyMarker(encode.apply(keyMarker));
		result.setVersionIdMarker(idMarker);
		result.setNextKeyMarker(listing.next().map(Marker::key).map(encode).orElse(null));
		result.setNextVersionIdMarker(listing.next().map(Marker::id).orElse(null));
		result.setMaxKeys(listed.limit());
		result.setDelimiter(encode.apply(query.getFirst(DELIMITER)));
		result.setIsTruncated(listing.truncated());
		result.setEncodingType(query.getFirst(ENCODING_TYPE));
		result.setEntries(listing.entries().stream().map(entry -> describe(entry, encode)).toList());
		result.setCommonPrefixes(prefixes(listing, encode));
		return result;
	}

	/* ListMultipartUploads, where the uploads of one key run in the order they were initiated */
	ListMultipartUploadsResult uploads(final String bucket, final MultivaluedMap<String, String> query)
		throws IOException, BlobStoreException {

		final UnaryOperator<String> encode = encoding(query.getFirst(ENCODING_TYPE));
		final String keyMarker = Objects.requireNonNullElse(query.getFirst("key-marker"), "");
		final String idMarker = Objects.requireNonNullElse(query.getFirst("upload-id-marker"), "");
		final ListQuery listed = query(query, keyMarker, Requests.limit(query.getFirst("max-uploads"))).toBuilder()
			.idMarker(idMarker).build();
		final Listing<Upload> listing = namespace.listUploads(bucket, listed);
		final ListMultipartUploadsResult result = new ListMultipartUploadsResult();

		result.setBucket(bucket);
		result.setKeyMarker(encode.apply(keyMarker));
		result.setUploadIdMarker(idMarker);
		result.setNextKeyMarker(listing.next().map(Marker::key).map(encode).orElse(null));
		result.setNextUploadIdMarker(listing.next().map(Marker::id).orElse(null));
		result.setPrefix(encode.apply(listed.prefix()));
		result.setDelimiter(encode.apply(query.getFirst(DELIMITER)));
		result.setMaxUploads(listed.limit());
		result.setIsTruncated(listing.truncated());
		result.setEncodingType(query.getFirst(ENCODING_TYPE));
		result.setUploads(listing.entries().stream().map(upload -> new org.unigrid.hedgehog.s3.entity.Upload(
			encode.apply(upload.key()), upload.uploadId(), Responses.owner(), Responses.owner(), STANDARD,
			upload.initiated(), upload.checksumAlgorithm())).toList()
		);
		result.setCommonPrefixes(prefixes(listing, encode));
		return result;
	}

	private static String resume(final String token) {
		return ContinuationToken.decode(token).orElseThrow(() -> new S3Exception(S3ErrorCode.InvalidArgument,
			"The continuation token provided is incorrect"
		));
	}

	private static Object describe(final VersionEntry entry, final UnaryOperator<String> encode) {
		final ObjectVersion version = entry.version();

		return version.deleteMarker() ? new DeleteMarker(encode.apply(version.key()), version.versionId(),
			entry.latest(), version.lastModified(), Responses.owner()) : new Version(encode.apply(version.key()),
			version.versionId(), entry.latest(), version.lastModified(), Responses.quoted(version.etag()),
			version.size(), STANDARD, Responses.owner(), new ArrayList<>(version.checksums().keySet()));
	}

	private static List<ObjectSummary> summaries(final Listing<ObjectVersion> listing, final boolean owned,
		final UnaryOperator<String> encode) {

		return listing.entries().stream().map(version -> new ObjectSummary(encode.apply(version.key()),
			version.lastModified(), Responses.quoted(version.etag()),
			new ArrayList<>(version.checksums().keySet()), null, version.size(), STANDARD,
			owned ? Responses.owner() : null)).toList();
	}
}
