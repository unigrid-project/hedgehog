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
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.code;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteBucketTaggingResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketTaggingResponse;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

public class BucketTaggingTest {
	private static final String BUCKET = "photos";

	private static Tag tag(final String key, final String value) {
		return Tag.builder().key(key).value(value).build();
	}

	private static Tagging tagging(final Tag... tags) {
		return Tagging.builder().tagSet(tags).build();
	}

	private static S3Node node() throws Exception {
		final S3Node node = S3Node.start();

		node.getClient().createBucket(request -> request.bucket(BUCKET));
		return node;
	}

	private static void putTags(final S3Client client, final Tag... tags) {
		client.putBucketTagging(request -> request.bucket(BUCKET).tagging(tagging(tags)));
	}

	@Example
	public void readsReplacesAndClearsTheTagsOfABucket() throws Exception {
		try (S3Node node = node()) {
			assertThat(code(() -> node.getClient().getBucketTagging(request -> request.bucket(BUCKET))),
				equalTo("NoSuchTagSet"));

			final PutBucketTaggingResponse put = node.getClient().putBucketTagging(request -> request
				.bucket(BUCKET).tagging(tagging(tag("env", "prod"), tag("team", "s3"))));

			assertThat(put.sdkHttpResponse().statusCode(), equalTo(HttpURLConnection.HTTP_NO_CONTENT));
			assertThat(node.getClient().getBucketTagging(request -> request.bucket(BUCKET)).tagSet(),
				containsInAnyOrder(tag("env", "prod"), tag("team", "s3"))
			);

			putTags(node.getClient(), tag("env", "test"));

			assertThat(node.getClient().getBucketTagging(request -> request.bucket(BUCKET)).tagSet(),
				contains(tag("env", "test"))
			);

			final DeleteBucketTaggingResponse deleted = node.getClient().deleteBucketTagging(request -> request
				.bucket(BUCKET));

			assertThat(deleted.sdkHttpResponse().statusCode(), equalTo(HttpURLConnection.HTTP_NO_CONTENT));
			assertThat(code(() -> node.getClient().getBucketTagging(request -> request.bucket(BUCKET))),
				equalTo("NoSuchTagSet"));
		}
	}

	@Example
	public void answersNoSuchBucketForABucketThatIsNotThere() throws Exception {
		try (S3Node node = S3Node.start()) {
			assertThrows(NoSuchBucketException.class, () -> node.getClient().getBucketTagging(request -> request
				.bucket(BUCKET)));
			assertThrows(NoSuchBucketException.class, () -> node.getClient().putBucketTagging(request -> request
				.bucket(BUCKET).tagging(tagging(tag("env", "prod")))));
			assertThrows(NoSuchBucketException.class, () -> node.getClient().deleteBucketTagging(request -> request
				.bucket(BUCKET)));
		}
	}

	@Example
	public void refusesMoreTagsAndInvalidTagsThanS3AllowsForABucket() throws Exception {
		try (S3Node node = node()) {
			assertThat(code(() -> putTags(node.getClient(), IntStream.range(0, 51)
				.mapToObj(number -> tag("key" + number, "value")).toArray(Tag[]::new))), equalTo("InvalidTag"));
			assertThat(code(() -> putTags(node.getClient(), tag("aws:reserved", "value"))),
				equalTo("InvalidTag")
			);
		}
	}
}
