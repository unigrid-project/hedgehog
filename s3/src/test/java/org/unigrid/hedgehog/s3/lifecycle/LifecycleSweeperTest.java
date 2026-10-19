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

package org.unigrid.hedgehog.s3.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;

/* The objects are written now and the sweeper runs at a clock set days ahead, which a day count rounded up to the
   next midnight lets pass exactly once the count plus one day is over */
public class LifecycleSweeperTest {
	private static final String BUCKET = "logs";

	private static S3Node node(final boolean versioned) throws Exception {
		final S3Node node = S3Node.start();

		node.getClient().createBucket(request -> request.bucket(BUCKET));

		if (versioned) {
			node.getClient().putBucketVersioning(request -> request.bucket(BUCKET).versioningConfiguration(
				versioning -> versioning.status(BucketVersioningStatus.ENABLED)));
		}

		return node;
	}

	private static void rules(final S3Client client, final String bucket, final Consumer<LifecycleRule.Builder> rule) {
		final LifecycleRule.Builder built = LifecycleRule.builder().id("rule").status(ExpirationStatus.ENABLED)
			.filter(filter -> filter.prefix(""));

		rule.accept(built);
		client.putBucketLifecycleConfiguration(request -> request.bucket(bucket).lifecycleConfiguration(
			configuration -> configuration.rules(built.build())));
	}

	private static String put(final S3Client client, final String bucket, final String key) {
		return client.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString(key)).versionId();
	}

	private static void sweep(final S3Node node, final int days) throws Exception {
		sweep(node, days, 1000);
	}

	private static void sweep(final S3Node node, final int days, final int page) throws Exception {
		try (LifecycleSweeper sweeper = new LifecycleSweeper(node.getNamespace(), Clock.offset(Clock.systemUTC(),
			Duration.ofDays(days)), Duration.ZERO, page)) {

			sweeper.sweep();
		}
	}

	private static List<String> keys(final S3Client client, final String bucket) {
		return client.listObjectsV2(request -> request.bucket(bucket)).contents().stream().map(S3Object::key)
			.toList();
	}

	private static List<String> versions(final S3Client client, final String bucket) {
		final ListObjectVersionsResponse listed = client.listObjectVersions(request -> request.bucket(bucket));

		return Stream.concat(listed.versions().stream().map(version -> version.key() + " " + version.versionId()),
			listed.deleteMarkers().stream().map(marker -> marker.key() + " marker")).toList();
	}

	@Example
	public void expiresObjectsOnceTheirDaysHavePassed() throws Exception {
		try (S3Node node = node(false)) {
			final S3Client client = node.getClient();

			rules(client, BUCKET, rule -> rule.filter(filter -> filter.prefix("old/")).expiration(expiration ->
				expiration.days(1)));
			put(client, BUCKET, "old/a");
			put(client, BUCKET, "new/b");

			sweep(node, 1);
			assertThat(keys(client, BUCKET), contains("new/b", "old/a"));
			sweep(node, 2);
			assertThat(keys(client, BUCKET), contains("new/b"));
			assertThat(versions(client, BUCKET), contains("new/b null"));
		}
	}

	@Example
	public void leavesADeleteMarkerWhereItExpiresAVersionedObject() throws Exception {
		try (S3Node node = node(true)) {
			final S3Client client = node.getClient();

			rules(client, BUCKET, rule -> rule.expiration(expiration -> expiration.days(1)));

			final String version = put(client, BUCKET, "a");

			sweep(node, 2);
			assertThat(keys(client, BUCKET), empty());
			assertThat(versions(client, BUCKET), containsInAnyOrder("a " + version, "a marker"));
		}
	}

	@Example
	public void expiresTheTaggedObjectsOnTheDateOfTheRule() throws Exception {
		try (S3Node node = node(false)) {
			final S3Client client = node.getClient();
			final Tag tag = Tag.builder().key("temporary").value("yes").build();

			rules(client, BUCKET, rule -> rule.filter(filter -> filter.tag(tag)).expiration(expiration -> expiration
				.date(Instant.parse("2020-01-01T00:00:00Z"))));
			client.putObject(request -> request.bucket(BUCKET).key("tagged").tagging("temporary=yes"),
				RequestBody.fromString("tagged"));
			put(client, BUCKET, "untagged");

			sweep(node, 0);
			assertThat(keys(client, BUCKET), contains("untagged"));
		}
	}

	@Example
	public void removesADeleteMarkerOnlyOnceItIsTheLastVersionOfItsKey() throws Exception {
		try (S3Node node = node(true)) {
			final S3Client client = node.getClient();

			rules(client, BUCKET, rule -> rule.expiration(expiration -> expiration.expiredObjectDeleteMarker(true)));

			final String alone = put(client, BUCKET, "alone");

			put(client, BUCKET, "kept");
			client.deleteObject(request -> request.bucket(BUCKET).key("alone"));
			client.deleteObject(request -> request.bucket(BUCKET).key("kept"));
			client.deleteObject(request -> request.bucket(BUCKET).key("alone").versionId(alone));

			sweep(node, 0);
			assertThat(versions(client, BUCKET).stream().map(version -> version.split(" ")[0]).toList(),
				contains("kept", "kept"));
		}
	}

	@Example
	public void expiresNoncurrentVersionsButTheNewestItIsToldToKeep() throws Exception {
		try (S3Node node = node(true)) {
			final S3Client client = node.getClient();
			final List<String> written = IntStream.range(0, 5).mapToObj(i -> put(client, BUCKET, "a")).toList();

			rules(client, BUCKET, rule -> rule.noncurrentVersionExpiration(noncurrent -> noncurrent.noncurrentDays(1)
				.newerNoncurrentVersions(1)));

			sweep(node, 1);
			assertThat(versions(client, BUCKET).size(), equalTo(5));
			sweep(node, 2);
			assertThat(versions(client, BUCKET), contains("a " + written.get(4), "a " + written.get(3)));
		}
	}

	/* Each key gathers its versions across pages before the sweeper changes any, so small pages give the same end */
	@Example
	public void pagesThroughBucketsWithManyKeysAndVersions() throws Exception {
		try (S3Node node = node(true)) {
			final S3Client client = node.getClient();
			final List<String> kept = new ArrayList<>();

			for (int key = 0; key < 4; key++) {
				final String name = "key" + key;
				final List<String> written = IntStream.range(0, 7).mapToObj(i -> put(client, BUCKET, name)).toList();

				kept.addAll(List.of(name + " " + written.get(6), name + " " + written.get(5), name + " "
					+ written.get(4)));
			}

			rules(client, BUCKET, rule -> rule.noncurrentVersionExpiration(noncurrent -> noncurrent.noncurrentDays(1)
				.newerNoncurrentVersions(2)));

			sweep(node, 2, 3);
			assertThat(versions(client, BUCKET), equalTo(kept));
		}
	}

	@Example
	public void abortsIncompleteUploadsUnderItsPrefix() throws Exception {
		try (S3Node node = node(false)) {
			final S3Client client = node.getClient();

			rules(client, BUCKET, rule -> rule.filter(filter -> filter.prefix("tmp/"))
				.abortIncompleteMultipartUpload(abort -> abort.daysAfterInitiation(1)));

			for (String key : List.of("tmp/a", "tmp/a", "tmp/b", "keep/c")) {
				client.createMultipartUpload(request -> request.bucket(BUCKET).key(key));
			}

			sweep(node, 1);
			assertThat(client.listMultipartUploads(request -> request.bucket(BUCKET)).uploads().size(), equalTo(4));
			sweep(node, 2, 1);
			assertThat(client.listMultipartUploads(request -> request.bucket(BUCKET)).uploads().stream()
				.map(MultipartUpload::key).toList(), contains("keep/c"));
		}
	}

	@Example
	public void neverRemovesAVersionObjectLockHolds() throws Exception {
		try (S3Node node = S3Node.start()) {
			final S3Client client = node.getClient();
			final String bucket = "vault";

			client.createBucket(request -> request.bucket(bucket).objectLockEnabledForBucket(true));

			final String governed = client.putObject(request -> request.bucket(bucket).key("a").objectLockMode(
				ObjectLockMode.GOVERNANCE).objectLockRetainUntilDate(Instant.now().plus(Duration.ofDays(365))),
				RequestBody.fromString("governed")).versionId();
			final String held = client.putObject(request -> request.bucket(bucket).key("a")
				.objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.ON), RequestBody.fromString("held"))
				.versionId();
			client.putObject(request -> request.bucket(bucket).key("a").objectLockMode(ObjectLockMode.COMPLIANCE)
				.objectLockRetainUntilDate(Instant.now().plus(Duration.ofDays(1))), RequestBody.fromString("past"));

			final String current = put(client, bucket, "a");

			rules(client, bucket, rule -> rule.expiration(expiration -> expiration.days(1))
				.noncurrentVersionExpiration(noncurrent -> noncurrent.noncurrentDays(1)));

			sweep(node, 30);
			assertThat(versions(client, bucket), containsInAnyOrder("a " + current, "a " + held, "a " + governed,
				"a marker"));
		}
	}

	@Example
	public void sweepsOnItsOwnEveryInterval() throws Exception {
		try (S3Node node = node(false); LifecycleSweeper sweeper = LifecycleSweeper.start(node.getNamespace(),
			Clock.systemUTC(), Duration.ofMillis(20))) {

			final S3Client client = node.getClient();
			final Instant deadline = Instant.now().plusSeconds(10);

			put(client, BUCKET, "a");
			rules(client, BUCKET, rule -> rule.expiration(expiration -> expiration.date(Instant.parse(
				"2020-01-01T00:00:00Z"))));

			while (!keys(client, BUCKET).isEmpty() && Instant.now().isBefore(deadline)) {
				Thread.sleep(20);
			}

			assertThat(keys(client, BUCKET), empty());
			assertThat(sweeper.getInterval(), equalTo(Duration.ofMillis(20)));
		}
	}
}
