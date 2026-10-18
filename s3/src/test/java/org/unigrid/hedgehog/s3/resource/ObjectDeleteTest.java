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
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.s3.index.Versioning;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.BUCKET;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.code;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.get;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.put;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class ObjectDeleteTest {
	private static final byte[] DATA = "content".getBytes(StandardCharsets.UTF_8);

	private static List<ObjectIdentifier> keys(final String... keys) {
		return List.of(keys).stream().map(key -> ObjectIdentifier.builder().key(key).build()).toList();
	}

	private static S3Node versioned() throws Exception {
		final S3Node node = ObjectResourceTest.node();

		node.getNamespace().updateBucket(BUCKET, bucket -> bucket.toBuilder().versioning(Versioning.ENABLED).build());
		return node;
	}

	@Example
	public void deletesAnObjectAndAnswersNoContentEvenWhenItIsMissing() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			put(node.getClient(), "key", DATA);

			for (int i = 0; i < 2; i++) {
				final DeleteObjectResponse deleted = node.getClient().deleteObject(request -> request.bucket(BUCKET)
					.key("key"));

				assertThat(deleted.sdkHttpResponse().statusCode(), equalTo(HttpURLConnection.HTTP_NO_CONTENT));
				assertThat(deleted.deleteMarker(), nullValue());
				assertThrows(NoSuchKeyException.class, () -> get(node.getClient(), "key"));
			}

			assertThrows(NoSuchBucketException.class, () -> node.getClient().deleteObject(request -> request
				.bucket("nowhere").key("key"))
			);
		}
	}

	@Example
	public void deletesManyObjectsAtOnce() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			for (String key : List.of("a", "b/c", "d e", "kept")) {
				put(node.getClient(), key, DATA);
			}

			final DeleteObjectsResponse deleted = node.getClient().deleteObjects(request -> request.bucket(BUCKET)
				.delete(delete -> delete.objects(keys("a", "b/c", "d e", "missing"))));

			assertThat(deleted.deleted().stream().map(DeletedObject::key).toList(),
				containsInAnyOrder("a", "b/c", "d e", "missing")
			);
			assertThat(deleted.errors(), empty());
			assertThat(get(node.getClient(), "kept").asByteArray(), equalTo(DATA));
			assertThrows(NoSuchKeyException.class, () -> get(node.getClient(), "b/c"));
		}
	}

	@Example
	public void answersNothingButErrorsWhenAskedToBeQuiet() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			put(node.getClient(), "a", DATA);

			final DeleteObjectsResponse deleted = node.getClient().deleteObjects(request -> request.bucket(BUCKET)
				.delete(delete -> delete.objects(keys("a", "x".repeat(1025))).quiet(true)));

			assertThat(deleted.deleted(), empty());
			assertThat(deleted.errors().stream().map(error -> error.code()).toList(), contains("KeyTooLongError"));
			assertThrows(NoSuchKeyException.class, () -> get(node.getClient(), "a"));
		}
	}

	@Example
	public void refusesMoreKeysThanOneRequestMayDelete() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final String[] many = IntStream.range(0, 1001).mapToObj(i -> "key" + i).toArray(String[]::new);

			assertThat(code(() -> node.getClient().deleteObjects(request -> request.bucket(BUCKET)
				.delete(delete -> delete.objects(keys(many))))), equalTo("MalformedXML")
			);
		}
	}

	@Example
	public void leavesADeleteMarkerInAVersionedBucket() throws Exception {
		try (S3Node node = versioned()) {
			final String version = node.getClient().putObject(request -> request.bucket(BUCKET).key("key"),
				RequestBody.fromBytes(DATA)).versionId();
			final DeleteObjectResponse deleted = node.getClient().deleteObject(request -> request.bucket(BUCKET)
				.key("key"));
			final S3Exception hidden = assertThrows(NoSuchKeyException.class, () -> get(node.getClient(), "key"));

			assertThat(version, notNullValue());
			assertThat(deleted.deleteMarker(), equalTo(true));
			assertThat(hidden.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("x-amz-delete-marker")
				.orElseThrow(), equalTo("true")
			);
			assertThat(assertThrows(S3Exception.class, () -> node.getClient().getObject(request -> request
				.bucket(BUCKET).key("key").versionId(deleted.versionId()))).statusCode(),
				equalTo(HttpURLConnection.HTTP_BAD_METHOD)
			);
			assertThat(node.getClient().getObjectAsBytes(request -> request.bucket(BUCKET).key("key")
				.versionId(version)).asByteArray(), equalTo(DATA)
			);

			final DeleteObjectResponse undeleted = node.getClient().deleteObject(request -> request.bucket(BUCKET)
				.key("key").versionId(deleted.versionId()));

			assertThat(undeleted.deleteMarker(), equalTo(true));
			assertThat(undeleted.versionId(), equalTo(deleted.versionId()));
			assertThat(get(node.getClient(), "key").asByteArray(), equalTo(DATA));
		}
	}

	@Example
	public void deletesVersionsAndMarkersAtOnce() throws Exception {
		try (S3Node node = versioned()) {
			put(node.getClient(), "a", DATA);
			put(node.getClient(), "b", DATA);

			final String marker = node.getClient().deleteObject(request -> request.bucket(BUCKET).key("b"))
				.versionId();
			final DeleteObjectsResponse deleted = node.getClient().deleteObjects(request -> request.bucket(BUCKET)
				.delete(delete -> delete.objects(ObjectIdentifier.builder().key("a").build(),
				ObjectIdentifier.builder().key("b").versionId(marker).build())));
			final DeletedObject a = deleted.deleted().stream().filter(object -> "a".equals(object.key())).findFirst()
				.orElseThrow();
			final DeletedObject b = deleted.deleted().stream().filter(object -> "b".equals(object.key())).findFirst()
				.orElseThrow();

			assertThat(a.deleteMarker(), equalTo(true));
			assertThat(a.deleteMarkerVersionId(), notNullValue());
			assertThat(b.versionId(), equalTo(marker));
			assertThat(b.deleteMarker(), equalTo(true));
			assertThat(b.deleteMarkerVersionId(), equalTo(marker));
			assertThat(get(node.getClient(), "b").asByteArray(), equalTo(DATA));
		}
	}
}
