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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.index.Versioning;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.BUCKET;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.code;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.get;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.md5;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.put;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

public class ObjectCopyTest {
	private static final byte[] DATA = new byte[50_000];
	private static final String SOURCE = "dir/a b+c€?.jpg";

	static {
		new Random(DATA.length).nextBytes(DATA);
	}

	private static S3Node node() throws Exception {
		final S3Node node = ObjectResourceTest.node();

		node.getClient().putObject(request -> request.bucket(BUCKET).key(SOURCE).contentType("image/jpeg")
			.metadata(Map.of("place", "Visby")), RequestBody.fromBytes(DATA)
		);

		return node;
	}

	private static CopyObjectResponse copy(final S3Node node, final Consumer<CopyObjectRequest.Builder> request) {

		return node.getClient().copyObject(copy -> request.accept(copy.sourceBucket(BUCKET).sourceKey(SOURCE)
			.destinationBucket(BUCKET).destinationKey("copy")));
	}

	private static HeadObjectResponse head(final S3Node node, final String bucket, final String key) {
		return node.getClient().headObject(request -> request.bucket(bucket).key(key));
	}

	@Example
	public void copiesTheObjectByReferenceWithItsHeadersAndMetadata() throws Exception {
		try (S3Node node = node()) {
			final CopyObjectResponse copied = copy(node, request -> { });
			final HeadObjectResponse head = head(node, BUCKET, "copy");

			assertThat(copied.copyObjectResult().eTag(), equalTo(md5(DATA)));
			assertThat(get(node.getClient(), "copy").asByteArray(), equalTo(DATA));
			assertThat(head.contentType(), equalTo("image/jpeg"));
			assertThat(head.metadata(), equalTo(Map.of("place", "Visby")));
			assertThat(head.eTag(), equalTo(md5(DATA)));
			assertThat(node.getBlobs().size(), equalTo(1));

			node.getClient().deleteObject(request -> request.bucket(BUCKET).key(SOURCE));
			assertThat(get(node.getClient(), "copy").asByteArray(), equalTo(DATA));
		}
	}

	@Example
	public void replacesTheHeadersAndMetadataAcrossBuckets() throws Exception {
		try (S3Node node = node()) {
			node.getClient().createBucket(request -> request.bucket("archive"));
			node.getClient().copyObject(request -> request.sourceBucket(BUCKET).sourceKey(SOURCE)
				.destinationBucket("archive").destinationKey("x").metadataDirective(MetadataDirective.REPLACE)
				.contentType("text/plain").metadata(Map.of("kept", "no"))
			);

			final HeadObjectResponse head = head(node, "archive", "x");

			assertThat(head.contentType(), equalTo("text/plain"));
			assertThat(head.metadata(), equalTo(Map.of("kept", "no")));
			assertThat(node.getClient().getObjectAsBytes(request -> request.bucket("archive").key("x"))
				.asByteArray(), equalTo(DATA)
			);
			assertThat(node.getBlobs().size(), equalTo(1));
		}
	}

	@Example
	public void copiesAnObjectOntoItselfOnlyToReplaceItsMetadata() throws Exception {
		try (S3Node node = node()) {
			assertThat(code(() -> node.getClient().copyObject(request -> request.sourceBucket(BUCKET)
				.sourceKey(SOURCE).destinationBucket(BUCKET).destinationKey(SOURCE))), equalTo("InvalidRequest")
			);

			node.getClient().copyObject(request -> request.sourceBucket(BUCKET).sourceKey(SOURCE)
				.destinationBucket(BUCKET).destinationKey(SOURCE).metadataDirective(MetadataDirective.REPLACE)
				.metadata(Map.of("place", "Fårö"))
			);

			assertThat(head(node, BUCKET, SOURCE).metadata(), equalTo(Map.of("place", "Fårö")));
		}
	}

	@Example
	public void copiesOnlyWhenTheSourceConditionsHold() throws Exception {
		try (S3Node node = node()) {
			final String etag = md5(DATA);
			final Instant hourAgo = Instant.now().minus(Duration.ofHours(1));

			assertThat(code(() -> copy(node, request -> request.copySourceIfMatch("\"other\""))),
				equalTo("PreconditionFailed")
			);
			assertThat(code(() -> copy(node, request -> request.copySourceIfNoneMatch(etag))),
				equalTo("PreconditionFailed")
			);
			assertThat(code(() -> copy(node, request -> request.copySourceIfUnmodifiedSince(hourAgo))),
				equalTo("PreconditionFailed")
			);

			copy(node, request -> request.copySourceIfMatch(etag).copySourceIfModifiedSince(hourAgo));
			assertThat(get(node.getClient(), "copy").asByteArray(), equalTo(DATA));
		}
	}

	@Example
	public void answersForASourceThatIsNotThere() throws Exception {
		try (S3Node node = node()) {
			assertThrows(NoSuchKeyException.class, () -> node.getClient().copyObject(request -> request
				.sourceBucket(BUCKET).sourceKey("missing").destinationBucket(BUCKET).destinationKey("copy"))
			);
			assertThrows(NoSuchBucketException.class, () -> node.getClient().copyObject(request -> request
				.sourceBucket("nowhere").sourceKey("missing").destinationBucket(BUCKET).destinationKey("copy"))
			);
		}
	}

	@Example
	public void copiesTheVersionItNames() throws Exception {
		try (S3Node node = node()) {
			node.getNamespace().updateBucket(BUCKET, bucket -> bucket.toBuilder().versioning(Versioning.ENABLED)
				.build());

			final String first = node.getClient().putObject(request -> request.bucket(BUCKET).key("v"),
				RequestBody.fromString("first")).versionId();

			put(node.getClient(), "v", "second".getBytes(StandardCharsets.UTF_8));

			final CopyObjectResponse copied = node.getClient().copyObject(request -> request.sourceBucket(BUCKET)
				.sourceKey("v").sourceVersionId(first).destinationBucket(BUCKET).destinationKey("restored"));

			assertThat(copied.copySourceVersionId(), equalTo(first));
			assertThat(get(node.getClient(), "restored").asUtf8String(), equalTo("first"));
		}
	}
}
