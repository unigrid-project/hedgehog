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

package org.unigrid.hedgehog.s3.server;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import org.unigrid.hedgehog.s3.jqwik.Expect;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class S3AuthenticationTest {
	private static final Duration HOUR = Duration.ofHours(1);

	private static String refusal(final Clock clock, final String secretKey, final Consumer<S3ClientBuilder> setup)
		throws Exception {

		final int port = S3Fixture.freePort();
		final S3ClientBuilder builder = S3Fixture.client(S3Fixture.endpoint(port), secretKey);

		setup.accept(builder);

		try (S3Server server = S3Fixture.start(port, clock); S3Client client = builder.build()) {
			return Expect.assertThrows(S3Exception.class, client::listBuckets).awsErrorDetails().errorCode();
		}
	}

	private static String body(final HttpURLConnection connection) throws Exception {
		return new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
	}

	@Example
	public void refusesAWrongSecretKey() throws Exception {
		assertThat(refusal(Clock.systemUTC(), "not-the-secret", builder -> { }), equalTo("SignatureDoesNotMatch"));
	}

	@Example
	public void refusesAnUnknownAccessKey() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port); S3Client client = S3Fixture.client(S3Fixture.endpoint(port),
			S3Fixture.SECRET_KEY).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials
			.create("HKUNKNOWN00000000000", S3Fixture.SECRET_KEY))).build()) {

			assertThat(Expect.assertThrows(S3Exception.class, client::listBuckets).awsErrorDetails().errorCode(),
				equalTo("InvalidAccessKeyId")
			);
		}
	}

	@Example
	public void refusesARequestSignedTooLongAgo() throws Exception {
		assertThat(refusal(Clock.offset(Clock.systemUTC(), HOUR), S3Fixture.SECRET_KEY,
			builder -> builder.overrideConfiguration(configuration -> configuration
				.retryStrategy(AwsRetryStrategy.doNotRetry()))), equalTo("RequestTimeTooSkewed")
		);
	}

	@Example
	public void letsAClientCorrectItsClockFromTheRefusal() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port, Clock.offset(Clock.systemUTC(), HOUR.negated()));
			S3Client client = S3Fixture.client(port)) {

			assertThat(client.listBuckets().buckets(), empty());
		}
	}

	@Example
	public void refusesAnUnsignedRequest() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port)) {
			final HttpURLConnection connection = (HttpURLConnection) S3Fixture.endpoint(port).toURL().openConnection();

			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_FORBIDDEN));
			assertThat(body(connection), matchesPattern("(?s).*<Error><Code>AccessDenied</Code>.*<RequestId>"
				+ connection.getHeaderField("x-amz-request-id") + "</RequestId></Error>")
			);
		}
	}

	@Example
	public void refusesAMalformedSignature() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port)) {
			final HttpURLConnection connection = S3Fixture.signed(port, "GET", "/");

			connection.setRequestProperty("Authorization", "AWS4-HMAC-SHA256 Credential=" + S3Fixture.ACCESS_KEY
				+ "/20261018/us-east-1/s3/aws4_request, Signature=00"
			);
			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_BAD_REQUEST));
			assertThat(body(connection), containsString("<Code>AuthorizationHeaderMalformed</Code>"));
		}
	}

	@Example
	public void refusesAnUnsignedAmazonHeader() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port)) {
			final HttpURLConnection connection = S3Fixture.signed(port, "GET", "/");

			connection.setRequestProperty("x-amz-meta-added", "later");
			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_FORBIDDEN));
			assertThat(body(connection), containsString("which were not signed"));
		}
	}
}
