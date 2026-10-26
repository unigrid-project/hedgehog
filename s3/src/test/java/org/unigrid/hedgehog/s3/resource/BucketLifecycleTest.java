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
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.BUCKET;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.code;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteBucketLifecycleResponse;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.Tag;

public class BucketLifecycleTest {
	private static final LifecycleExpiration NONE = null;

	private static LifecycleRule rule(final String id, final Consumer<LifecycleRule.Builder> change) {
		final LifecycleRule.Builder rule = LifecycleRule.builder().id(id).status(ExpirationStatus.ENABLED)
			.filter(filter -> filter.prefix("logs/")).expiration(expiration -> expiration.days(30));

		change.accept(rule);
		return rule.build();
	}

	private static void configure(final S3Client client, final LifecycleRule... rules) {
		client.putBucketLifecycleConfiguration(request -> request.bucket(BUCKET).lifecycleConfiguration(
			configuration -> configuration.rules(rules)));
	}

	private static String refusal(final S3Client client, final Consumer<LifecycleRule.Builder> change) {
		return code(() -> configure(client, rule("broken", change)));
	}

	@Example
	public void keepsReplacesAndDeletesTheRulesOfABucket() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final S3Client client = node.getClient();

			assertThat(code(() -> client.getBucketLifecycleConfiguration(request -> request.bucket(BUCKET))),
				equalTo("NoSuchLifecycleConfiguration"));

			configure(client, rule("logs", rule -> { }), rule("uploads", rule -> rule.expiration(NONE)
				.filter(filter -> filter.prefix("")).abortIncompleteMultipartUpload(abort -> abort
				.daysAfterInitiation(7))), rule("versions", rule -> rule.expiration(NONE)
				.noncurrentVersionExpiration(noncurrent -> noncurrent.noncurrentDays(3).newerNoncurrentVersions(2))
				.filter(filter -> filter.and(and -> and.prefix("a/").tags(Tag.builder().key("k").value("v").build())
				.objectSizeGreaterThan(10L)))));

			final List<LifecycleRule> kept = client.getBucketLifecycleConfiguration(request -> request
				.bucket(BUCKET)).rules();

			assertThat(kept.stream().map(LifecycleRule::id).toList(), contains("logs", "uploads", "versions"));
			assertThat(kept.getFirst().expiration().days(), equalTo(30));
			assertThat(kept.get(1).abortIncompleteMultipartUpload().daysAfterInitiation(), equalTo(7));
			assertThat(kept.get(2).noncurrentVersionExpiration().newerNoncurrentVersions(), equalTo(2));
			assertThat(kept.get(2).filter().and().tags(), contains(Tag.builder().key("k").value("v").build()));

			configure(client, rule("only", rule -> rule.status(ExpirationStatus.DISABLED)));

			assertThat(client.getBucketLifecycleConfiguration(request -> request.bucket(BUCKET)).rules().stream()
				.map(LifecycleRule::id).toList(), contains("only"));

			final DeleteBucketLifecycleResponse deleted = client.deleteBucketLifecycle(request -> request
				.bucket(BUCKET));

			assertThat(deleted.sdkHttpResponse().statusCode(), equalTo(HttpURLConnection.HTTP_NO_CONTENT));
			assertThat(code(() -> client.getBucketLifecycleConfiguration(request -> request.bucket(BUCKET))),
				equalTo("NoSuchLifecycleConfiguration"));
			assertThat(code(() -> client.getBucketLifecycleConfiguration(request -> request.bucket("nowhere"))),
				equalTo("NoSuchBucket"));
		}
	}

	@Example
	public void refusesRulesS3WouldRefuse() throws Exception {
		try (S3Node node = ObjectResourceTest.node()) {
			final S3Client client = node.getClient();

			assertThat(code(() -> configure(client, rule("twice", rule -> { }), rule("twice", rule -> { }))),
				equalTo("InvalidArgument"));
			assertThat(code(() -> configure(client, IntStream.range(0, 1001).mapToObj(i -> rule("rule" + i,
				rule -> { })).toArray(LifecycleRule[]::new))), equalTo("InvalidRequest"));
			assertThat(refusal(client, rule -> rule.status("Sometimes")), equalTo("MalformedXML"));
			assertThat(refusal(client, rule -> rule.id("x".repeat(256))), equalTo("InvalidArgument"));
			assertThat(refusal(client, rule -> rule.expiration(NONE)), equalTo("InvalidRequest"));
			assertThat(refusal(client, rule -> rule.expiration(expiration -> expiration.days(0))),
				equalTo("InvalidArgument"));
			assertThat(refusal(client, rule -> rule.expiration(expiration -> expiration.days(1)
				.expiredObjectDeleteMarker(true))), equalTo("MalformedXML"));
			assertThat(refusal(client, rule -> rule.expiration(expiration -> expiration.date(Instant.parse(
				"2030-01-01T12:00:00Z")))), equalTo("InvalidArgument"));
			assertThat(refusal(client, rule -> rule.filter(LifecycleRuleFilter.builder().prefix("a/")
				.tag(Tag.builder().key("k").value("v").build()).build())), equalTo("MalformedXML"));
			assertThat(refusal(client, rule -> rule.filter(filter -> filter.and(and -> and.objectSizeGreaterThan(10L)
				.objectSizeLessThan(10L)))), equalTo("InvalidArgument"));
			assertThat(refusal(client, rule -> rule.noncurrentVersionExpiration(noncurrent -> noncurrent
				.noncurrentDays(0))), equalTo("InvalidArgument"));
			assertThat(refusal(client, rule -> rule.filter(filter -> filter.tag(Tag.builder().key("k").value("v")
				.build())).abortIncompleteMultipartUpload(abort -> abort.daysAfterInitiation(1))),
				equalTo("InvalidRequest"));
			assertThat(refusal(client, rule -> rule.filter(filter -> filter.tag(Tag.builder().key("k").value("v")
				.build())).expiration(expiration -> expiration.expiredObjectDeleteMarker(true))),
				equalTo("InvalidArgument"));
			configure(client, rule("date", rule -> rule.expiration(expiration -> expiration.date(Instant.parse(
				"2030-01-01T00:00:00Z")))));
		}
	}
}
