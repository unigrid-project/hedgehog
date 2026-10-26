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

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.s3.index.Versioning;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.BUCKET;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.code;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.get;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.put;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.TaggingDirective;

public class ObjectTaggingTest {
	private static final String KEY = "tagged";
	private static final byte[] DATA = "content".getBytes(StandardCharsets.UTF_8);

	private static Tag tag(final String key, final String value) {
		return Tag.builder().key(key).value(value).build();
	}

	private static Tagging tagging(final Tag... tags) {
		return Tagging.builder().tagSet(tags).build();
	}

	private static void putTags(final S3Client client, final String key, final Tag... tags) {
		client.putObjectTagging(request -> request.bucket(BUCKET).key(key).tagging(tagging(tags)));
	}

	private static List<Tag> tags(final S3Client client, final String key) {
		return client.getObjectTagging(request -> request.bucket(BUCKET).key(key)).tagSet();
	}

	private static S3Node versioned() throws Exception {
		final S3Node node = ObjectResourceTest.node();

		node.getNamespace().updateBucket(BUCKET, bucket -> bucket.toBuilder().versioning(Versioning.ENABLED).build());
		return node;
	}

	private static void copy(final S3Node node, final String destination,
		final Consumer<CopyObjectRequest.Builder> change) {

		node.getClient().copyObject(request -> change.accept(request.sourceBucket(BUCKET).sourceKey(KEY)
			.destinationBucket(BUCKET).destinationKey(destination)));
	}

	@Example
	public void replacesReadsAndClearsTheTagsOfAnObject() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			put(node.getClient(), KEY, DATA);
			putTags(node.getClient(), KEY, tag("env", "prod"), tag("team", "s3"));

			assertThat(tags(node.getClient(), KEY), containsInAnyOrder(tag("env", "prod"), tag("team", "s3")));

			putTags(node.getClient(), KEY, tag("env", "test"));

			assertThat(tags(node.getClient(), KEY), contains(tag("env", "test")));

			final DeleteObjectTaggingResponse deleted = node.getClient().deleteObjectTagging(request -> request
				.bucket(BUCKET).key(KEY));

			assertThat(deleted.sdkHttpResponse().statusCode(), equalTo(HttpURLConnection.HTTP_NO_CONTENT));
			assertThat(tags(node.getClient(), KEY), empty());
		}
	}

	@Example
	public void keepsTheTagsOfAPutInTheHeaderAndCountsThem() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			node.getClient().putObject(request -> request.bucket(BUCKET).key(KEY).tagging("env=prod&note=a%20b"),
				RequestBody.fromBytes(DATA));
			put(node.getClient(), "plain", DATA);

			assertThat(tags(node.getClient(), KEY), containsInAnyOrder(tag("env", "prod"), tag("note", "a b")));
			assertThat(get(node.getClient(), KEY).response().tagCount(), equalTo(2));
			assertThat(get(node.getClient(), "plain").response().tagCount(), nullValue());
		}
	}

	@Example
	public void tagsTheVersionItNames() throws Exception {
		try (S3Node node = versioned()) {
			final String older = node.getClient().putObject(request -> request.bucket(BUCKET).key(KEY),
				RequestBody.fromString("older")).versionId();
			final String newer = node.getClient().putObject(request -> request.bucket(BUCKET).key(KEY),
				RequestBody.fromString("newer")).versionId();
			final PutObjectTaggingResponse tagged = node.getClient().putObjectTagging(request -> request
				.bucket(BUCKET).key(KEY).versionId(older).tagging(tagging(tag("env", "prod"))));

			assertThat(tagged.versionId(), equalTo(older));
			assertThat(node.getClient().getObjectTagging(request -> request.bucket(BUCKET).key(KEY)
				.versionId(older)).versionId(), equalTo(older));
			assertThat(node.getClient().getObjectTagging(request -> request.bucket(BUCKET).key(KEY)
				.versionId(older)).tagSet(), contains(tag("env", "prod")));
			assertThat(node.getClient().getObjectTagging(request -> request.bucket(BUCKET).key(KEY)
				.versionId(newer)).tagSet(), empty());

			node.getClient().deleteObjectTagging(request -> request.bucket(BUCKET).key(KEY).versionId(older));

			assertThat(node.getClient().getObjectTagging(request -> request.bucket(BUCKET).key(KEY)
				.versionId(older)).tagSet(), empty());
			assertThat(get(node.getClient(), KEY).asUtf8String(), equalTo("newer"));
		}
	}

	@Example
	public void answersAsGetObjectDoesForADeleteMarker() throws Exception {
		try (S3Node node = versioned()) {
			put(node.getClient(), KEY, DATA);

			final String marker = node.getClient().deleteObject(request -> request.bucket(BUCKET).key(KEY))
				.versionId();

			assertThrows(NoSuchKeyException.class, () -> tags(node.getClient(), KEY));
			assertThat(assertThrows(S3Exception.class, () -> node.getClient().getObjectTagging(request -> request
				.bucket(BUCKET).key(KEY).versionId(marker))).statusCode(),
				equalTo(HttpURLConnection.HTTP_BAD_METHOD)
			);
		}
	}

	@Example
	public void answersNoSuchKeyAndNoSuchBucket() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			assertThrows(NoSuchKeyException.class, () -> tags(node.getClient(), "missing"));
			assertThrows(NoSuchKeyException.class, () -> putTags(node.getClient(), "missing",
				tag("env", "prod")));
			assertThrows(NoSuchKeyException.class, () -> node.getClient().deleteObjectTagging(request -> request
				.bucket(BUCKET).key("missing")));
			assertThrows(NoSuchBucketException.class, () -> node.getClient().getObjectTagging(request -> request
				.bucket("nowhere").key(KEY)));
		}
	}

	@Example
	public void copiesOrReplacesTheTagsOfACopy() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			put(node.getClient(), KEY, DATA);
			putTags(node.getClient(), KEY, tag("env", "prod"));

			copy(node, "copied", request -> { });
			copy(node, "replaced", request -> request.taggingDirective(TaggingDirective.REPLACE)
				.tagging("env=test"));
			copy(node, "cleared", request -> request.taggingDirective(TaggingDirective.REPLACE));

			assertThat(tags(node.getClient(), "copied"), contains(tag("env", "prod")));
			assertThat(tags(node.getClient(), "replaced"), contains(tag("env", "test")));
			assertThat(tags(node.getClient(), "cleared"), empty());
		}
	}

	@Example
	public void keepsTheTagsOfAMultipartUpload() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final String uploadId = node.getClient().createMultipartUpload(request -> request.bucket(BUCKET)
				.key(KEY).tagging("env=prod")).uploadId();
			final String etag = node.getClient().uploadPart(request -> request.bucket(BUCKET).key(KEY)
				.uploadId(uploadId).partNumber(1), RequestBody.fromBytes(DATA)).eTag();

			node.getClient().completeMultipartUpload(request -> request.bucket(BUCKET).key(KEY).uploadId(uploadId)
				.multipartUpload(upload -> upload.parts(List.of(CompletedPart.builder().partNumber(1).eTag(etag)
					.build()))));

			assertThat(tags(node.getClient(), KEY), contains(tag("env", "prod")));
		}
	}

	@Example
	public void takesTheLargestTagSetS3Allows() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			put(node.getClient(), KEY, DATA);
			putTags(node.getClient(), KEY, IntStream.range(0, 10).mapToObj(number -> tag(
				number == 0 ? "k".repeat(128) : "key" + number, "v".repeat(256))).toArray(Tag[]::new));

			assertThat(tags(node.getClient(), KEY), hasSize(10));
		}
	}

	@Example
	public void refusesTagsOutsideTheLimits() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			put(node.getClient(), KEY, DATA);

			assertThat(code(() -> putTags(node.getClient(), KEY, IntStream.range(0, 11)
				.mapToObj(number -> tag("key" + number, "value")).toArray(Tag[]::new))), equalTo("InvalidTag"));
			assertThat(code(() -> putTags(node.getClient(), KEY, tag("", "value"))), equalTo("InvalidTag"));
			assertThat(code(() -> putTags(node.getClient(), KEY, tag("k".repeat(129), "value"))),
				equalTo("InvalidTag")
			);
			assertThat(code(() -> putTags(node.getClient(), KEY, tag("key", "v".repeat(257)))),
				equalTo("InvalidTag")
			);
			assertThat(code(() -> putTags(node.getClient(), KEY, tag("aws:reserved", "value"))),
				equalTo("InvalidTag")
			);
			assertThat(code(() -> putTags(node.getClient(), KEY, tag("duplicate", "first"),
				tag("duplicate", "second"))), equalTo("InvalidTag")
			);
			assertThat(code(() -> node.getClient().putObject(request -> request.bucket(BUCKET).key("header")
				.tagging("aws:reserved=value"), RequestBody.fromBytes(DATA))), equalTo("InvalidTag"));
			assertThat(tags(node.getClient(), KEY), empty());
		}
	}
}
