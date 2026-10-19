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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.code;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.status;
import org.unigrid.hedgehog.s3.server.S3Fixture;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;

public class ObjectLockTest {
	private static final String LOCKED = "vault";
	private static final String KEY = "record";

	private static S3Node node() throws Exception {
		final S3Node node = ObjectResourceTest.node();

		node.getClient().createBucket(request -> request.bucket(LOCKED).objectLockEnabledForBucket(true));
		return node;
	}

	private static Instant later(final Duration duration) {
		return Instant.now().plus(duration).truncatedTo(ChronoUnit.SECONDS);
	}

	private static String put(final S3Client client, final Consumer<PutObjectRequest.Builder> change) {
		return client.putObject(request -> change.accept(request.bucket(LOCKED).key(KEY)),
			RequestBody.fromString("content")).versionId();
	}

	private static String locked(final S3Client client, final ObjectLockMode mode, final Instant until) {
		return put(client, request -> request.objectLockMode(mode).objectLockRetainUntilDate(until));
	}

	private static void delete(final S3Client client, final String versionId, final boolean bypass) {
		client.deleteObject(request -> request.bucket(LOCKED).key(KEY).versionId(versionId)
			.bypassGovernanceRetention(bypass));
	}

	private static void retain(final S3Client client, final String versionId, final ObjectLockRetentionMode mode,
		final Instant until, final boolean bypass) {

		client.putObjectRetention(request -> request.bucket(LOCKED).key(KEY).versionId(versionId)
			.bypassGovernanceRetention(bypass).retention(retention -> retention.mode(mode).retainUntilDate(until)));
	}

	private static HeadObjectResponse head(final S3Client client, final String versionId) {
		return client.headObject(request -> request.bucket(LOCKED).key(KEY).versionId(versionId));
	}

	@Example
	public void createsALockedBucketWhoseVersioningStaysOn() throws Exception {
		try (S3Node node = node()) {
			final S3Client client = node.getClient();
			final ObjectLockConfiguration configuration = client.getObjectLockConfiguration(request -> request
				.bucket(LOCKED)).objectLockConfiguration();

			assertThat(configuration.objectLockEnabled(), equalTo(ObjectLockEnabled.ENABLED));
			assertThat(configuration.rule(), nullValue());
			assertThat(client.getBucketVersioning(request -> request.bucket(LOCKED)).status(),
				equalTo(BucketVersioningStatus.ENABLED));
			assertThat(code(() -> client.putBucketVersioning(request -> request.bucket(LOCKED)
				.versioningConfiguration(versioning -> versioning.status(BucketVersioningStatus.SUSPENDED)))),
				equalTo("InvalidBucketState"));
			assertThat(code(() -> client.getObjectLockConfiguration(request -> request
				.bucket(ObjectResourceTest.BUCKET))), equalTo("ObjectLockConfigurationNotFoundError"));
		}
	}

	@Example
	public void locksAnExistingBucketOnlyOnceVersioningIsOn() throws Exception {
		try (S3Node node = node()) {
			final S3Client client = node.getClient();
			final Consumer<ObjectLockConfiguration.Builder> yearly = configuration -> configuration
				.objectLockEnabled(ObjectLockEnabled.ENABLED).rule(rule -> rule.defaultRetention(retention ->
				retention.mode(ObjectLockRetentionMode.COMPLIANCE).years(1)));

			assertThat(code(() -> client.putObjectLockConfiguration(request -> request
				.bucket(ObjectResourceTest.BUCKET).objectLockConfiguration(yearly))), equalTo("InvalidBucketState"));
			client.putBucketVersioning(request -> request.bucket(ObjectResourceTest.BUCKET)
				.versioningConfiguration(versioning -> versioning.status(BucketVersioningStatus.ENABLED)));
			client.putObjectLockConfiguration(request -> request.bucket(ObjectResourceTest.BUCKET)
				.objectLockConfiguration(yearly));

			assertThat(client.getObjectLockConfiguration(request -> request.bucket(ObjectResourceTest.BUCKET))
				.objectLockConfiguration().rule().defaultRetention().years(), equalTo(1));
			assertThat(code(() -> client.putObjectLockConfiguration(request -> request.bucket(LOCKED)
				.objectLockConfiguration(configuration -> configuration.objectLockEnabled(ObjectLockEnabled.ENABLED)
				.rule(rule -> rule.defaultRetention(retention -> retention.mode(ObjectLockRetentionMode.GOVERNANCE)
				.days(1).years(1)))))), equalTo("MalformedXML"));
			assertThat(code(() -> client.putObjectLockConfiguration(request -> request.bucket(LOCKED)
				.objectLockConfiguration(configuration -> configuration.objectLockEnabled(ObjectLockEnabled.ENABLED)
				.rule(rule -> rule.defaultRetention(retention -> retention.mode(ObjectLockRetentionMode.GOVERNANCE)
				.days(0)))))), equalTo("InvalidArgument"));
		}
	}

	@Example
	public void retainsWhatABucketWritesForItsDefaultPeriod() throws Exception {
		try (S3Node node = node()) {
			final S3Client client = node.getClient();

			client.putObjectLockConfiguration(request -> request.bucket(LOCKED).objectLockConfiguration(
				configuration -> configuration.objectLockEnabled(ObjectLockEnabled.ENABLED).rule(rule -> rule
				.defaultRetention(retention -> retention.mode(ObjectLockRetentionMode.GOVERNANCE).days(2)))));

			final Instant before = Instant.now();
			final String version = put(client, request -> { });
			final HeadObjectResponse head = head(client, version);

			assertThat(head.objectLockMode(), equalTo(ObjectLockMode.GOVERNANCE));
			assertThat(head.objectLockRetainUntilDate(), greaterThan(before.plus(Duration.ofDays(2))
				.minusSeconds(1)));
			assertThat(head.objectLockRetainUntilDate(), lessThanOrEqualTo(Instant.now().plus(Duration.ofDays(2))));
			assertThat(head.objectLockLegalHoldStatus(), equalTo(ObjectLockLegalHoldStatus.OFF));
			assertThat(client.getObjectRetention(request -> request.bucket(LOCKED).key(KEY).versionId(version))
				.retention().mode(), equalTo(ObjectLockRetentionMode.GOVERNANCE));
			assertThat(code(() -> delete(client, version, false)), equalTo("AccessDenied"));
		}
	}

	@Example
	public void deletesAGovernedVersionOnlyWhenTheRequestBypassesGovernance() throws Exception {
		try (S3Node node = node()) {
			final S3Client client = node.getClient();
			final Instant until = later(Duration.ofDays(1));
			final String version = locked(client, ObjectLockMode.GOVERNANCE, until);

			assertThat(head(client, version).objectLockRetainUntilDate(), equalTo(until));
			assertThat(code(() -> delete(client, version, false)), equalTo("AccessDenied"));

			final String marker = client.deleteObject(request -> request.bucket(LOCKED).key(KEY)).versionId();

			delete(client, marker, false);
			assertThat(head(client, null).versionId(), equalTo(version));
			delete(client, version, true);
			assertThat(status(() -> head(client, version)), equalTo(HttpURLConnection.HTTP_NOT_FOUND));
		}
	}

	@Example
	public void neverShortensOrLiftsAComplianceRetention() throws Exception {
		try (S3Node node = node()) {
			final S3Client client = node.getClient();
			final Instant until = later(Duration.ofDays(2));
			final String version = locked(client, ObjectLockMode.COMPLIANCE, until);

			assertThat(code(() -> delete(client, version, true)), equalTo("AccessDenied"));
			assertThat(code(() -> retain(client, version, ObjectLockRetentionMode.COMPLIANCE,
				until.minusSeconds(60), true)), equalTo("AccessDenied"));
			assertThat(code(() -> retain(client, version, ObjectLockRetentionMode.GOVERNANCE, until.plusSeconds(60),
				true)), equalTo("AccessDenied"));

			retain(client, version, ObjectLockRetentionMode.COMPLIANCE, until.plusSeconds(60), false);

			assertThat(head(client, version).objectLockRetainUntilDate(), equalTo(until.plusSeconds(60)));
		}
	}

	@Example
	public void changesAGovernanceRetentionToShorterOnlyWhenBypassing() throws Exception {
		try (S3Node node = node()) {
			final S3Client client = node.getClient();
			final Instant until = later(Duration.ofDays(2));
			final String version = locked(client, ObjectLockMode.GOVERNANCE, until);

			assertThat(code(() -> retain(client, version, ObjectLockRetentionMode.GOVERNANCE, until.minusSeconds(60),
				false)), equalTo("AccessDenied"));
			retain(client, version, ObjectLockRetentionMode.GOVERNANCE, until.plusSeconds(60), false);
			retain(client, version, ObjectLockRetentionMode.GOVERNANCE, until.minusSeconds(60), true);

			assertThat(client.getObjectRetention(request -> request.bucket(LOCKED).key(KEY).versionId(version))
				.retention().retainUntilDate(), equalTo(until.minusSeconds(60)));
			assertThat(code(() -> retain(client, version, ObjectLockRetentionMode.GOVERNANCE,
				Instant.now().minusSeconds(60), true)), equalTo("InvalidArgument"));
		}
	}

	@Example
	public void keepsAVersionUnderLegalHoldUntilTheHoldIsLifted() throws Exception {
		try (S3Node node = node()) {
			final S3Client client = node.getClient();
			final String version = put(client, request -> request.objectLockLegalHoldStatus(
				ObjectLockLegalHoldStatus.ON));

			assertThat(head(client, version).objectLockLegalHoldStatus(), equalTo(ObjectLockLegalHoldStatus.ON));
			assertThat(head(client, version).objectLockMode(), nullValue());
			assertThat(code(() -> client.getObjectRetention(request -> request.bucket(LOCKED).key(KEY))),
				equalTo("NoSuchObjectLockConfiguration"));
			assertThat(code(() -> delete(client, version, true)), equalTo("AccessDenied"));

			client.putObjectLegalHold(request -> request.bucket(LOCKED).key(KEY).versionId(version)
				.legalHold(hold -> hold.status(ObjectLockLegalHoldStatus.OFF)));

			assertThat(client.getObjectLegalHold(request -> request.bucket(LOCKED).key(KEY)).legalHold().status(),
				equalTo(ObjectLockLegalHoldStatus.OFF));
			delete(client, version, false);
		}
	}

	@Example
	public void locksWhatACopyOrAMultipartUploadStoresTheWayItsRequestAsks() throws Exception {
		try (S3Node node = node()) {
			final S3Client client = node.getClient();
			final Instant until = later(Duration.ofDays(1));

			locked(client, ObjectLockMode.GOVERNANCE, until.plusSeconds(60));

			final String copy = client.copyObject(request -> request.sourceBucket(LOCKED).sourceKey(KEY)
				.destinationBucket(LOCKED).destinationKey("copy").objectLockMode(ObjectLockMode.COMPLIANCE)
				.objectLockRetainUntilDate(until)).versionId();
			final HeadObjectResponse copied = client.headObject(request -> request.bucket(LOCKED).key("copy")
				.versionId(copy));
			final String upload = client.createMultipartUpload(request -> request.bucket(LOCKED).key("parts")
				.objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.ON)).uploadId();
			final String etag = client.uploadPart(request -> request.bucket(LOCKED).key("parts").uploadId(upload)
				.partNumber(1), RequestBody.fromString("part")).eTag();
			final String completed = client.completeMultipartUpload(request -> request.bucket(LOCKED).key("parts")
				.uploadId(upload).multipartUpload(parts -> parts.parts(CompletedPart.builder().partNumber(1)
				.eTag(etag).build()))).versionId();
			final HeadObjectResponse assembled = client.headObject(request -> request.bucket(LOCKED).key("parts")
				.versionId(completed));

			assertThat(copied.objectLockMode(), equalTo(ObjectLockMode.COMPLIANCE));
			assertThat(copied.objectLockRetainUntilDate(), equalTo(until));
			assertThat(assembled.objectLockLegalHoldStatus(), equalTo(ObjectLockLegalHoldStatus.ON));
			assertThat(assembled.objectLockMode(), nullValue());
		}
	}

	@Example
	public void reportsTheLockedVersionsADeleteOfManyLeaves() throws Exception {
		try (S3Node node = node()) {
			final S3Client client = node.getClient();
			final String governed = locked(client, ObjectLockMode.GOVERNANCE, later(Duration.ofDays(1)));
			final String free = put(client, request -> { });
			final DeleteObjectsResponse deleted = client.deleteObjects(request -> request.bucket(LOCKED)
				.delete(delete -> delete.objects(ObjectIdentifier.builder().key(KEY).versionId(governed).build(),
				ObjectIdentifier.builder().key(KEY).versionId(free).build())));

			assertThat(deleted.deleted().stream().map(DeletedObject::versionId).toList(), contains(free));
			assertThat(deleted.errors().stream().map(error -> error.versionId() + " " + error.code()).toList(),
				contains(governed + " AccessDenied"));
			assertThat(client.deleteObjects(request -> request.bucket(LOCKED).bypassGovernanceRetention(true)
				.delete(delete -> delete.objects(ObjectIdentifier.builder().key(KEY).versionId(governed).build())))
				.errors().stream().map(S3Error::code).toList(), empty());
		}
	}

	@Example
	public void refusesRetentionInABucketWithoutObjectLock() throws Exception {
		try (S3Node node = node()) {
			final S3Client client = node.getClient();

			ObjectResourceTest.put(client, KEY, "content".getBytes(StandardCharsets.UTF_8));

			assertThat(code(() -> client.putObject(request -> request.bucket(ObjectResourceTest.BUCKET).key(KEY)
				.objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.ON), RequestBody.fromString("content"))),
				equalTo("InvalidRequest"));
			assertThat(code(() -> client.getObjectRetention(request -> request.bucket(ObjectResourceTest.BUCKET)
				.key(KEY))), equalTo("InvalidRequest"));
			assertThat(code(() -> client.putObjectLegalHold(request -> request.bucket(ObjectResourceTest.BUCKET)
				.key(KEY).legalHold(hold -> hold.status(ObjectLockLegalHoldStatus.ON)))), equalTo("InvalidRequest"));
			assertThat(code(() -> locked(client, ObjectLockMode.GOVERNANCE, Instant.now().minusSeconds(60))),
				equalTo("InvalidArgument"));
		}
	}

	/* S3 has the body of every request that locks a version or configures locking carry a digest */
	@Example
	public void requiresADigestOfWhatLocksAVersion() throws Exception {
		try (S3Node node = node(); S3Client undigested = S3Fixture.client(S3Fixture.endpoint(node.getPort()),
			S3Fixture.SECRET_KEY).requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).build()) {

			final S3Client client = node.getClient();

			put(client, request -> { });

			assertThat(code(() -> locked(undigested, ObjectLockMode.GOVERNANCE, later(Duration.ofDays(1)))),
				equalTo("InvalidRequest"));
			assertThat(refusal(node, "/" + LOCKED + "/" + KEY + "?retention"), containsString("InvalidRequest"));
			assertThat(refusal(node, "/" + LOCKED + "/" + KEY + "?legal-hold"), containsString("InvalidRequest"));
			assertThat(refusal(node, "/" + LOCKED + "?object-lock"), containsString("InvalidRequest"));
		}
	}

	private static String refusal(final S3Node node, final String path) throws IOException {
		final HttpURLConnection connection = S3Fixture.signed(node.getPort(), "PUT", path);

		assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_BAD_REQUEST));
		return new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
	}
}
