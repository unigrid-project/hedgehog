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
import java.time.Instant;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.index.Upload;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.s3.server.S3Fixture;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class BucketResourceTest {
	private static String code(final S3Exception error) {
		return error.awsErrorDetails().errorCode();
	}

	@Example
	public void createsListsAndDeletesBuckets() throws Exception {
		try (S3Node node = S3Node.start()) {
			node.getClient().createBucket(request -> request.bucket("photos"));
			node.getClient().createBucket(request -> request.bucket("music"));
			node.getClient().headBucket(request -> request.bucket("photos"));

			final ListBucketsResponse listed = node.getClient().listBuckets();

			assertThat(listed.buckets().stream().map(Bucket::name).toList(), contains("music", "photos"));
			assertThat(listed.buckets().getFirst().creationDate(), notNullValue());
			assertThat(listed.owner().id(), notNullValue());

			node.getClient().deleteBucket(request -> request.bucket("photos"));
			assertThat(node.getClient().listBuckets().buckets().stream().map(Bucket::name).toList(),
				contains("music")
			);
		}
	}

	@Example
	public void refusesToCreateABucketTwice() throws Exception {
		try (S3Node node = S3Node.start()) {
			node.getClient().createBucket(request -> request.bucket("photos"));

			assertThrows(BucketAlreadyOwnedByYouException.class, () -> node.getClient()
				.createBucket(request -> request.bucket("photos"))
			);
		}
	}

	@Example
	public void refusesAnInvalidBucketName() throws Exception {
		try (S3Node node = S3Node.start()) {
			final HttpURLConnection connection = S3Fixture.signed(node.getPort(), "PUT", "/Not_Valid");

			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_BAD_REQUEST));
			assertThat(new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8),
				containsString("<Code>InvalidBucketName</Code>")
			);
		}
	}

	@Example
	public void answersNoSuchBucketForABucketThatIsNotThere() throws Exception {
		try (S3Node node = S3Node.start()) {
			assertThrows(NoSuchBucketException.class, () -> node.getClient().headBucket(request -> request
				.bucket("photos"))
			);
			assertThat(code(assertThrows(S3Exception.class, () -> node.getClient().deleteBucket(request -> request
				.bucket("photos")))), equalTo("NoSuchBucket")
			);
			assertThat(code(assertThrows(S3Exception.class, () -> node.getClient().getBucketLocation(request -> request
				.bucket("photos")))), equalTo("NoSuchBucket")
			);
		}
	}

	@Example
	public void refusesToDeleteABucketWithAnUploadUnderWay() throws Exception {
		try (S3Node node = S3Node.start()) {
			node.getClient().createBucket(request -> request.bucket("photos"));
			node.getNamespace().createUpload(Upload.builder().template(ObjectVersion.builder().bucket("photos")
				.key("big.raw").lastModified(Instant.now()).build()).build()
			);

			assertThat(code(assertThrows(S3Exception.class, () -> node.getClient().deleteBucket(request -> request
				.bucket("photos")))), equalTo("BucketNotEmpty")
			);
		}
	}

	@Example
	public void keepsTheLocationConstraintOfABucket() throws Exception {
		try (S3Node node = S3Node.start()) {
			node.getClient().createBucket(request -> request.bucket("photos").createBucketConfiguration(
				configuration -> configuration.locationConstraint("eu-north-1"))
			);
			node.getClient().createBucket(request -> request.bucket("music"));

			assertThat(node.getClient().getBucketLocation(request -> request.bucket("photos"))
				.locationConstraintAsString(), equalTo("eu-north-1")
			);
			assertThat(node.getClient().getBucketLocation(request -> request.bucket("music"))
				.locationConstraintAsString(), equalTo("")
			);
		}
	}

	@Example
	public void answersNotImplementedForABucketSubresourceItLacks() throws Exception {
		try (S3Node node = S3Node.start()) {
			node.getClient().createBucket(request -> request.bucket("photos"));

			assertThat(assertThrows(S3Exception.class, () -> node.getClient().getBucketPolicy(request -> request
				.bucket("photos"))).statusCode(), equalTo(HttpURLConnection.HTTP_NOT_IMPLEMENTED)
			);
			assertThat(code(assertThrows(S3Exception.class, () -> node.getClient().putBucketPolicy(request -> request
				.bucket("photos").policy("{}")))), equalTo("NotImplemented")
			);
		}
	}
}
