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

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.BUCKET;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.code;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.get;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;

/* The SDK client here is the synchronous one, which uploads parts as told rather than splitting files itself */
public class MultipartTest {
	private static final int PART = 5 << 20;
	private static final byte[] DATA = new byte[2 * PART + 12_345];

	static {
		new Random(DATA.length).nextBytes(DATA);
	}

	private static String create(final S3Client client, final String key) {
		return client.createMultipartUpload(request -> request.bucket(BUCKET).key(key).contentType("video/mp4")
			.metadata(Map.of("scene", "1"))).uploadId();
	}

	private static CompletedPart upload(final S3Client client, final String key, final String uploadId,
		final int number, final byte[] data, final int from, final int to) {

		final String etag = client.uploadPart(request -> request.bucket(BUCKET).key(key).uploadId(uploadId)
			.partNumber(number), RequestBody.fromBytes(Arrays.copyOfRange(data, from, to))).eTag();

		return CompletedPart.builder().partNumber(number).eTag(etag).build();
	}

	private static List<CompletedPart> uploadAll(final S3Client client, final String key, final String uploadId) {
		final List<CompletedPart> parts = new ArrayList<>();

		for (int from = 0, number = 1; from < DATA.length; from += PART, number++) {
			parts.add(upload(client, key, uploadId, number, DATA, from, Math.min(from + PART, DATA.length)));
		}

		return parts;
	}

	private static String complete(final S3Client client, final String key, final String uploadId,
		final List<CompletedPart> parts) {

		return client.completeMultipartUpload(request -> request.bucket(BUCKET).key(key).uploadId(uploadId)
			.multipartUpload(upload -> upload.parts(parts))).eTag();
	}

	private static String etagOfParts(final byte[]... parts) throws Exception {
		final ByteArrayOutputStream md5s = new ByteArrayOutputStream();

		for (byte[] part : parts) {
			md5s.write(MessageDigest.getInstance("MD5").digest(part));
		}

		return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(md5s.toByteArray())) + "-"
			+ parts.length + "\"";
	}

	@Example
	public void uploadsAnObjectInPartsAndReadsItBackAcrossThem() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final String uploadId = create(node.getClient(), "movie");
			final String etag = complete(node.getClient(), "movie", uploadId, uploadAll(node.getClient(), "movie",
				uploadId));
			final HeadObjectResponse head = node.getClient().headObject(request -> request.bucket(BUCKET)
				.key("movie"));

			assertThat(etag, equalTo(etagOfParts(Arrays.copyOfRange(DATA, 0, PART),
				Arrays.copyOfRange(DATA, PART, 2 * PART), Arrays.copyOfRange(DATA, 2 * PART, DATA.length)))
			);
			assertThat(get(node.getClient(), "movie").asByteArray(), equalTo(DATA));
			assertThat(head.eTag(), equalTo(etag));
			assertThat(head.contentLength(), equalTo((long) DATA.length));
			assertThat(head.contentType(), equalTo("video/mp4"));
			assertThat(head.metadata(), equalTo(Map.of("scene", "1")));
			assertThat(node.getClient().getObjectAsBytes(request -> request.bucket(BUCKET).key("movie")
				.range("bytes=" + (PART - 10) + "-" + (2 * PART + 9))).asByteArray(),
				equalTo(Arrays.copyOfRange(DATA, PART - 10, 2 * PART + 10))
			);
			assertThat(node.getClient().listMultipartUploads(request -> request.bucket(BUCKET)).uploads(), empty());
			assertThat(node.getBlobs().size(), equalTo(3));
		}
	}

	@Example
	public void takesTheLastUploadOfAPart() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final String uploadId = create(node.getClient(), "key");

			upload(node.getClient(), "key", uploadId, 1, DATA, 0, 10);

			final CompletedPart part = upload(node.getClient(), "key", uploadId, 1, DATA, 10, 30);

			complete(node.getClient(), "key", uploadId, List.of(part));
			assertThat(get(node.getClient(), "key").asByteArray(), equalTo(Arrays.copyOfRange(DATA, 10, 30)));
		}
	}

	@Example
	public void refusesToCompleteWithPartsItWasNotSent() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final String uploadId = create(node.getClient(), "key");
			final CompletedPart first = upload(node.getClient(), "key", uploadId, 1, DATA, 0, PART);
			final CompletedPart second = upload(node.getClient(), "key", uploadId, 2, DATA, 0, 10);
			final CompletedPart small = upload(node.getClient(), "key", uploadId, 3, DATA, 0, 10);
			final CompletedPart wrong = first.toBuilder().eTag("\"0123456789abcdef0123456789abcdef\"").build();
			final CompletedPart missing = first.toBuilder().partNumber(9).build();

			assertThat(code(() -> complete(node.getClient(), "key", uploadId, List.of(wrong, second))),
				equalTo("InvalidPart")
			);
			assertThat(code(() -> complete(node.getClient(), "key", uploadId, List.of(first, missing))),
				equalTo("InvalidPart")
			);
			assertThat(code(() -> complete(node.getClient(), "key", uploadId, List.of(second, first))),
				equalTo("InvalidPartOrder")
			);
			assertThat(code(() -> complete(node.getClient(), "key", uploadId, List.of(second, small))),
				equalTo("EntityTooSmall")
			);
			assertThat(code(() -> complete(node.getClient(), "key", uploadId, List.of())), equalTo("MalformedXML"));
			assertThat(code(() -> complete(node.getClient(), "key", "0000000000000099", List.of(first))),
				equalTo("NoSuchUpload")
			);

			complete(node.getClient(), "key", uploadId, List.of(first, small));
			assertThat(get(node.getClient(), "key").asByteArray().length, equalTo(PART + 10));
			assertThat(code(() -> complete(node.getClient(), "key", uploadId, List.of(first))),
				equalTo("NoSuchUpload")
			);
		}
	}

	@Example
	public void refusesAPartNumberOutsideTheRange() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final String uploadId = create(node.getClient(), "key");

			for (int number : new int[] { 0, 10_001 }) {
				assertThat(code(() -> upload(node.getClient(), "key", uploadId, number, DATA, 0, 10)),
					equalTo("InvalidArgument")
				);
			}
		}
	}

	@Example
	public void abortsAnUploadWithItsParts() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final String uploadId = create(node.getClient(), "key");

			upload(node.getClient(), "key", uploadId, 1, DATA, 0, 10);
			node.getClient().abortMultipartUpload(request -> request.bucket(BUCKET).key("key").uploadId(uploadId));

			assertThrows(NoSuchUploadException.class, () -> node.getClient().listParts(request -> request
				.bucket(BUCKET).key("key").uploadId(uploadId))
			);
			assertThrows(NoSuchUploadException.class, () -> upload(node.getClient(), "key", uploadId, 2, DATA, 0,
				10)
			);
			assertThrows(NoSuchUploadException.class, () -> node.getClient().abortMultipartUpload(request -> request
				.bucket(BUCKET).key("key").uploadId(uploadId))
			);
			node.getClient().deleteBucket(request -> request.bucket(BUCKET));
		}
	}

	@Example
	public void listsThePartsPageByPage() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final String uploadId = create(node.getClient(), "key");

			for (int number = 1; number <= 5; number++) {
				upload(node.getClient(), "key", uploadId, number, DATA, 0, number);
			}

			final ListPartsResponse first = node.getClient().listParts(request -> request.bucket(BUCKET).key("key")
				.uploadId(uploadId).maxParts(2));
			final ListPartsResponse rest = node.getClient().listParts(request -> request.bucket(BUCKET).key("key")
				.uploadId(uploadId).partNumberMarker(first.nextPartNumberMarker()));

			assertThat(first.parts().stream().map(part -> part.partNumber()).toList(), contains(1, 2));
			assertThat(first.isTruncated(), equalTo(true));
			assertThat(first.nextPartNumberMarker(), equalTo(2));
			assertThat(rest.parts().stream().map(part -> part.size()).toList(), contains(3L, 4L, 5L));
			assertThat(rest.isTruncated(), equalTo(false));
			assertThat(rest.owner().id(), equalTo("hedgehog"));
		}
	}

	@Example
	public void listsTheUploadsUnderWay() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final String first = create(node.getClient(), "a/1");

			create(node.getClient(), "a/2");
			create(node.getClient(), "b");

			final String second = create(node.getClient(), "b");
			final ListMultipartUploadsResponse rolled = node.getClient().listMultipartUploads(request -> request
				.bucket(BUCKET).delimiter("/"));
			final ListMultipartUploadsResponse page = node.getClient().listMultipartUploads(request -> request
				.bucket(BUCKET).maxUploads(1).keyMarker("a/2"));

			assertThat(rolled.commonPrefixes().stream().map(prefix -> prefix.prefix()).toList(), contains("a/"));
			assertThat(rolled.uploads().stream().map(MultipartUpload::key).toList(), contains("b", "b"));
			assertThat(page.uploads().stream().map(MultipartUpload::key).toList(), contains("b"));
			assertThat(page.isTruncated(), equalTo(true));

			final ListMultipartUploadsResponse next = node.getClient().listMultipartUploads(request -> request
				.bucket(BUCKET).keyMarker(page.nextKeyMarker()).uploadIdMarker(page.nextUploadIdMarker()));

			assertThat(next.uploads().stream().map(MultipartUpload::uploadId).toList(), contains(second));
			assertThat(node.getClient().listMultipartUploads(request -> request.bucket(BUCKET).prefix("a/"))
				.uploads().getFirst().uploadId(), equalTo(first)
			);
		}
	}

	@Example
	public void copiesPartsFromAnotherObjectByReference() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			ObjectResourceTest.put(node.getClient(), "source", DATA);

			final String uploadId = create(node.getClient(), "copy");
			final List<CompletedPart> parts = new ArrayList<>();

			for (int from = 0, number = 1; from < DATA.length; from += PART, number++) {
				final int start = from;
				final int partNumber = number;
				final String etag = node.getClient().uploadPartCopy(request -> request.sourceBucket(BUCKET)
					.sourceKey("source").destinationBucket(BUCKET).destinationKey("copy").uploadId(uploadId)
					.partNumber(partNumber).copySourceRange("bytes=" + start + "-"
					+ (Math.min(start + PART, DATA.length) - 1))).copyPartResult().eTag();

				parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
			}

			complete(node.getClient(), "copy", uploadId, parts);

			assertThat(get(node.getClient(), "copy").asByteArray(), equalTo(DATA));
			assertThat(node.getBlobs().size(), equalTo(1));
			assertThat(code(() -> node.getClient().uploadPartCopy(request -> request.sourceBucket(BUCKET)
				.sourceKey("source").destinationBucket(BUCKET).destinationKey("copy").uploadId(create(node
				.getClient(), "copy")).partNumber(1).copySourceRange("bytes=0-" + DATA.length))),
				equalTo("InvalidArgument")
			);
		}
	}

	@Example
	public void copiesAWholeObjectAsAPartWithItsOwnEtag() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			ObjectResourceTest.put(node.getClient(), "source", Arrays.copyOfRange(DATA, 0, 100));

			final String uploadId = create(node.getClient(), "copy");
			final String etag = node.getClient().uploadPartCopy(request -> request.sourceBucket(BUCKET)
				.sourceKey("source").destinationBucket(BUCKET).destinationKey("copy").uploadId(uploadId)
				.partNumber(1)).copyPartResult().eTag();

			assertThat(etag, equalTo(ObjectResourceTest.md5(Arrays.copyOfRange(DATA, 0, 100))));
			assertThat(node.getClient().listParts(request -> request.bucket(BUCKET).key("copy").uploadId(uploadId))
				.parts().getFirst().size(), equalTo(100L)
			);
			assertThat(node.getClient().listMultipartUploads(request -> request.bucket(BUCKET)).uploads()
				.getFirst().key(), equalTo("copy")
			);
			assertThat(node.getClient().listParts(request -> request.bucket(BUCKET).key("copy").uploadId(uploadId))
				.nextPartNumberMarker(), nullValue()
			);
		}
	}
}
