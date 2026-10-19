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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.BUCKET;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.code;
import org.unigrid.hedgehog.s3.server.S3Fixture;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.MFADelete;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class VersioningTest {
	private static void versioning(final S3Client client, final BucketVersioningStatus status) {
		client.putBucketVersioning(request -> request.bucket(BUCKET).versioningConfiguration(configuration ->
			configuration.status(status)));
	}

	private static String put(final S3Client client, final String key, final String content) {
		return client.putObject(request -> request.bucket(BUCKET).key(key), RequestBody.fromString(content))
			.versionId();
	}

	private static String read(final S3Client client, final String key, final String versionId) {
		return client.getObjectAsBytes(request -> request.bucket(BUCKET).key(key).versionId(versionId))
			.asUtf8String();
	}

	private static ListObjectVersionsResponse list(final S3Client client) {
		return client.listObjectVersions(request -> request.bucket(BUCKET));
	}

	private static List<String> describe(final ListObjectVersionsResponse listed) {
		return Stream.concat(listed.versions().stream().map(version -> version.key() + " " + version.versionId()
			+ " " + version.isLatest()), listed.deleteMarkers().stream().map(marker -> marker.key() + " "
			+ marker.versionId() + " " + marker.isLatest() + " marker")).toList();
	}

	@Example
	public void turnsVersioningOnAndSuspendsIt() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final S3Client client = node.getClient();

			assertThat(client.getBucketVersioning(request -> request.bucket(BUCKET)).status(), nullValue());
			versioning(client, BucketVersioningStatus.ENABLED);
			assertThat(client.getBucketVersioning(request -> request.bucket(BUCKET)).status(),
				equalTo(BucketVersioningStatus.ENABLED));
			versioning(client, BucketVersioningStatus.SUSPENDED);
			assertThat(client.getBucketVersioning(request -> request.bucket(BUCKET)).status(),
				equalTo(BucketVersioningStatus.SUSPENDED));

			assertThat(code(() -> client.putBucketVersioning(request -> request.bucket(BUCKET)
				.versioningConfiguration(configuration -> configuration.status("Unversioned")))),
				equalTo("IllegalVersioningConfigurationException"));
			assertThat(code(() -> client.putBucketVersioning(request -> request.bucket(BUCKET)
				.versioningConfiguration(configuration -> configuration.status(BucketVersioningStatus.ENABLED)
				.mfaDelete(MFADelete.ENABLED)))), equalTo("NotImplemented"));
			assertThat(code(() -> client.getBucketVersioning(request -> request.bucket("nowhere"))),
				equalTo("NoSuchBucket"));
		}
	}

	/* Tools such as rclone ask every bucket for its versioning before they purge it */
	@Example
	public void answersABucketThatNeverHadVersioningWithAnEmptyConfiguration() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final HttpURLConnection connection = S3Fixture.signed(node.getPort(), "GET", "/" + BUCKET
				+ "?versioning");

			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_OK));
			assertThat(new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
				containsString("<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>"));
		}
	}

	@Example
	public void listsVersionsAndDeleteMarkersNewestFirst() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final S3Client client = node.getClient();

			versioning(client, BucketVersioningStatus.ENABLED);

			final String first = put(client, "a", "first");
			final String second = put(client, "a", "second");
			final String marker = client.deleteObject(request -> request.bucket(BUCKET).key("a")).versionId();
			final String other = put(client, "b", "other");
			final ListObjectVersionsResponse listed = list(client);

			assertThat(describe(listed), contains("a " + second + " false", "a " + first + " false",
				"b " + other + " true", "a " + marker + " true marker"));
			assertThat(listed.versions().getFirst().eTag(), equalTo(ObjectResourceTest.md5("second".getBytes())));
			assertThat(listed.versions().getFirst().size(), equalTo(6L));
			assertThat(listed.isTruncated(), equalTo(false));
		}
	}

	@Example
	public void pagesThroughVersionsAndRollsThemUpAtTheDelimiter() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final S3Client client = node.getClient();
			final List<String> all = new ArrayList<>();

			versioning(client, BucketVersioningStatus.ENABLED);

			for (String key : List.of("a", "a", "b/1", "b/2", "c", "c", "c")) {
				put(client, key, key);
			}

			ListObjectVersionsRequest request = ListObjectVersionsRequest.builder().bucket(BUCKET).maxKeys(2).build();

			for (boolean more = true; more;) {
				final ListObjectVersionsResponse page = client.listObjectVersions(request);

				assertThat(page.versions().size() <= 2, equalTo(true));
				page.versions().forEach(version -> all.add(version.key() + " " + version.versionId()));
				more = page.isTruncated();
				request = request.toBuilder().keyMarker(page.nextKeyMarker())
					.versionIdMarker(page.nextVersionIdMarker()).build();
			}

			assertThat(all, equalTo(list(client).versions().stream().map(version -> version.key() + " "
				+ version.versionId()).toList()));
			assertThat(all.size(), equalTo(7));

			final ListObjectVersionsResponse rolled = client.listObjectVersions(builder -> builder.bucket(BUCKET)
				.delimiter("/"));

			assertThat(rolled.commonPrefixes().stream().map(CommonPrefix::prefix).toList(), contains("b/"));
			assertThat(rolled.versions().stream().map(ObjectVersion::key).toList(),
				contains("a", "a", "c", "c", "c"));
			assertThat(client.listObjectVersions(builder -> builder.bucket(BUCKET).prefix("b/")).versions().stream()
				.map(ObjectVersion::key).toList(), contains("b/1", "b/2"));
			assertThat(code(() -> client.listObjectVersions(builder -> builder.bucket(BUCKET)
				.versionIdMarker("null"))), equalTo("InvalidArgument"));
		}
	}

	@Example
	public void readsHeadsCopiesAndDeletesTheVersionItNames() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final S3Client client = node.getClient();

			versioning(client, BucketVersioningStatus.ENABLED);

			final String first = put(client, "a", "first");
			final String second = put(client, "a", "second");

			assertThat(read(client, "a", first), equalTo("first"));
			assertThat(client.headObject(request -> request.bucket(BUCKET).key("a").versionId(first))
				.contentLength(), equalTo(5L));
			assertThat(client.headObject(request -> request.bucket(BUCKET).key("a")).versionId(), equalTo(second));

			final DeleteObjectResponse deleted = client.deleteObject(request -> request.bucket(BUCKET).key("a")
				.versionId(second));

			assertThat(deleted.versionId(), equalTo(second));
			assertThat(deleted.deleteMarker(), nullValue());
			assertThat(read(client, "a", null), equalTo("first"));
			assertThat(code(() -> read(client, "a", second)), equalTo("NoSuchVersion"));
			assertThat(client.copyObject(request -> request.sourceBucket(BUCKET).sourceKey("a").sourceVersionId(first)
				.destinationBucket(BUCKET).destinationKey("b")).copySourceVersionId(), equalTo(first));
			assertThat(read(client, "b", null), equalTo("first"));
		}
	}

	@Example
	public void answersForADeleteMarkerTheWayS3Does() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final S3Client client = node.getClient();

			versioning(client, BucketVersioningStatus.ENABLED);
			put(client, "a", "content");

			final String marker = client.deleteObject(request -> request.bucket(BUCKET).key("a")).versionId();
			final S3Exception hidden = assertThrows(NoSuchKeyException.class, () -> client.headObject(request ->
				request.bucket(BUCKET).key("a")));
			final S3Exception named = assertThrows(S3Exception.class, () -> client.getObject(request -> request
				.bucket(BUCKET).key("a").versionId(marker)));

			assertThat(hidden.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("x-amz-delete-marker")
				.orElseThrow(), equalTo("true"));
			assertThat(named.statusCode(), equalTo(HttpURLConnection.HTTP_BAD_METHOD));
			assertThat(named.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("x-amz-delete-marker")
				.orElseThrow(), equalTo("true"));
			assertThat(named.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("x-amz-version-id")
				.orElseThrow(), equalTo(marker));
		}
	}

	/* A suspended bucket keeps the versions it has, and writes over the null version and only that one */
	@Example
	public void keepsOneNullVersionOnceVersioningIsSuspended() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final S3Client client = node.getClient();

			assertThat(put(client, "a", "unversioned"), nullValue());
			assertThat(client.headObject(request -> request.bucket(BUCKET).key("a")).versionId(), nullValue());
			versioning(client, BucketVersioningStatus.ENABLED);
			assertThat(client.headObject(request -> request.bucket(BUCKET).key("a")).versionId(), equalTo("null"));

			final String versioned = put(client, "a", "versioned");

			versioning(client, BucketVersioningStatus.SUSPENDED);
			assertThat(put(client, "a", "suspended"), equalTo("null"));
			assertThat(describe(list(client)), contains("a null true", "a " + versioned + " false"));
			assertThat(read(client, "a", "null"), equalTo("suspended"));

			final DeleteObjectResponse deleted = client.deleteObject(request -> request.bucket(BUCKET).key("a"));

			assertThat(deleted.deleteMarker(), equalTo(true));
			assertThat(deleted.versionId(), equalTo("null"));
			assertThat(describe(list(client)), contains("a " + versioned + " false", "a null true marker"));
			assertThat(list(client).deleteMarkers().stream().map(DeleteMarkerEntry::versionId).toList(),
				contains("null"));
			assertThat(read(client, "a", versioned), equalTo("versioned"));
		}
	}
}
