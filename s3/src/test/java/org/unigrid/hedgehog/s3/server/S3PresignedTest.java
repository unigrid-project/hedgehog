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
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

public class S3PresignedTest {
	private static final String KEY = "photos/a b+c€.jpg";

	private static URL presign(final int port, final Duration validity) {
		try (S3Presigner presigner = S3Presigner.builder().endpointOverride(S3Fixture.endpoint(port))
			.region(Region.US_EAST_1).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
			.build()).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
			S3Fixture.ACCESS_KEY, S3Fixture.SECRET_KEY))).build()) {

			return presigner.presignGetObject(request -> request.signatureDuration(validity)
				.getObjectRequest(get -> get.bucket("bucket").key(KEY))).url();
		}
	}

	private static HttpURLConnection get(final URL url) throws Exception {
		return (HttpURLConnection) url.openConnection();
	}

	private static String error(final HttpURLConnection connection) throws Exception {
		return new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
	}

	@Example
	public void servesAPresignedUrl() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port, EchoResource.class)) {
			final HttpURLConnection connection = get(presign(port, Duration.ofMinutes(5)));

			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_OK));
			assertThat(new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
				equalTo("bucket/" + KEY)
			);
		}
	}

	@Example
	public void refusesAnExpiredPresignedUrl() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(10)),
			EchoResource.class)) {

			final HttpURLConnection connection = get(presign(port, Duration.ofMinutes(5)));

			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_FORBIDDEN));
			assertThat(error(connection), containsString("<Message>Request has expired</Message>"));
		}
	}

	@Example
	public void refusesATamperedPresignedUrl() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port, EchoResource.class)) {
			final URL url = presign(port, Duration.ofMinutes(5));
			final HttpURLConnection connection = get(URI.create(url.toString().replace("photos", "videos")).toURL());

			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_FORBIDDEN));
			assertThat(error(connection), containsString("<Code>SignatureDoesNotMatch</Code>"));
		}
	}

	@Example
	public void refusesTwoWaysOfSigningAtOnce() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port, EchoResource.class)) {
			final HttpURLConnection connection = get(presign(port, Duration.ofMinutes(5)));

			connection.setRequestProperty("Authorization", "AWS4-HMAC-SHA256 Credential=x");
			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_BAD_REQUEST));
			assertThat(error(connection), containsString("<Code>InvalidArgument</Code>"));
		}
	}
}
