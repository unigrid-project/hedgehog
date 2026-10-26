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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.auth.SdkSigner;
import org.unigrid.hedgehog.s3.auth.SigV4;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.code;
import static org.unigrid.hedgehog.s3.resource.ObjectResourceTest.status;
import org.unigrid.hedgehog.s3.server.S3Fixture;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.DeleteBucketCorsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketCorsResponse;

public class BucketCorsTest {
	private static final String BUCKET = "photos";
	private static final String DOCUMENT = "<CORSConfiguration><CORSRule>"
		+ "<AllowedOrigin>https://app.example</AllowedOrigin><AllowedMethod>GET</AllowedMethod>"
		+ "</CORSRule></CORSConfiguration>";

	private static final CORSRule UPLOADS = CORSRule.builder().id("uploads").allowedOrigins("https://*.example")
		.allowedMethods("PUT", "POST").allowedHeaders("*").exposeHeaders("ETag").maxAgeSeconds(3000).build();
	private static final CORSRule READS = CORSRule.builder().allowedOrigins("*").allowedMethods("GET", "HEAD")
		.build();

	private static S3Node node() throws Exception {
		final S3Node node = S3Node.start();

		node.getClient().createBucket(request -> request.bucket(BUCKET));
		return node;
	}

	private static PutBucketCorsResponse putRules(final S3Client client, final CORSRule... rules) {
		return client.putBucketCors(request -> request.bucket(BUCKET).corsConfiguration(CORSConfiguration.builder()
			.corsRules(rules).build())
		);
	}

	private static String noRules(final S3Client client) {
		return code(() -> client.getBucketCors(request -> request.bucket(BUCKET)));
	}

	/* What the SDK always adds, an integrity header, is left to the test here */
	private static HttpURLConnection put(final int port, final String document, final boolean digested)
		throws Exception {

		final byte[] body = document.getBytes(StandardCharsets.UTF_8);
		final URI uri = S3Fixture.endpoint(port).resolve("/" + BUCKET + "?cors");
		final SdkHttpRequest.Builder unsigned = SdkHttpRequest.builder().uri(uri).method(SdkHttpMethod.PUT)
			.putHeader("x-amz-content-sha256", SigV4.sha256Hex(body));

		if (digested) {
			unsigned.putHeader("Content-MD5", Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5")
				.digest(body)));
		}

		final SdkHttpRequest request = SdkSigner.sign(unsigned.build(), body, S3Fixture.ACCESS_KEY,
			S3Fixture.SECRET_KEY, Clock.systemUTC()
		);
		final HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();

		connection.setRequestMethod("PUT");
		connection.setDoOutput(true);
		request.headers().forEach((name, values) -> connection.setRequestProperty(name, values.getFirst()));

		try (OutputStream output = connection.getOutputStream()) {
			output.write(body);
		}

		return connection;
	}

	@Example
	public void readsReplacesAndDeletesTheCorsRulesOfABucket() throws Exception {
		try (S3Node node = node()) {
			assertThat(noRules(node.getClient()), equalTo("NoSuchCORSConfiguration"));
			assertThat(status(() -> node.getClient().getBucketCors(request -> request.bucket(BUCKET))),
				equalTo(HttpURLConnection.HTTP_NOT_FOUND)
			);
			assertThat(putRules(node.getClient(), UPLOADS, READS).sdkHttpResponse().statusCode(),
				equalTo(HttpURLConnection.HTTP_OK)
			);
			assertThat(node.getClient().getBucketCors(request -> request.bucket(BUCKET)).corsRules(),
				contains(UPLOADS, READS)
			);

			putRules(node.getClient(), READS);

			assertThat(node.getClient().getBucketCors(request -> request.bucket(BUCKET)).corsRules(),
				contains(READS)
			);

			final DeleteBucketCorsResponse deleted = node.getClient().deleteBucketCors(request -> request
				.bucket(BUCKET));

			assertThat(deleted.sdkHttpResponse().statusCode(), equalTo(HttpURLConnection.HTTP_NO_CONTENT));
			assertThat(noRules(node.getClient()), equalTo("NoSuchCORSConfiguration"));
		}
	}

	@Example
	public void answersWithTheDocumentAsItWasSent() throws Exception {
		try (S3Node node = node()) {
			assertThat(put(node.getPort(), DOCUMENT, true).getResponseCode(),
				equalTo(HttpURLConnection.HTTP_OK)
			);

			final HttpURLConnection read = S3Fixture.signed(node.getPort(), "GET", "/" + BUCKET + "?cors");

			assertThat(read.getResponseCode(), equalTo(HttpURLConnection.HTTP_OK));
			assertThat(new String(read.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
				equalTo(DOCUMENT)
			);
		}
	}

	@Example
	public void requiresAnIntegrityHeaderLikeS3() throws Exception {
		try (S3Node node = node()) {
			final HttpURLConnection connection = put(node.getPort(), DOCUMENT, false);

			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_BAD_REQUEST));
			assertThat(new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
				.contains("<Code>InvalidRequest</Code>"), equalTo(true)
			);
			assertThat(noRules(node.getClient()), equalTo("NoSuchCORSConfiguration"));
		}
	}

	@Example
	public void answersNoSuchBucketForABucketThatIsNotThere() throws Exception {
		try (S3Node node = S3Node.start()) {
			assertThrows(NoSuchBucketException.class, () -> node.getClient().getBucketCors(request -> request
				.bucket(BUCKET)));
			assertThrows(NoSuchBucketException.class, () -> putRules(node.getClient(), READS));
			assertThrows(NoSuchBucketException.class, () -> node.getClient().deleteBucketCors(request -> request
				.bucket(BUCKET)));
		}
	}

	@Example
	public void refusesRulesS3Refuses() throws Exception {
		try (S3Node node = node()) {
			final S3Client client = node.getClient();

			assertThat(code(() -> putRules(client, READS.toBuilder().allowedMethods("GET", "PATCH").build())),
				equalTo("InvalidRequest")
			);
			assertThat(code(() -> putRules(client, READS.toBuilder().allowedOrigins("https://*.*.example")
				.build())), equalTo("InvalidRequest")
			);
			assertThat(code(() -> putRules(client, READS.toBuilder().allowedHeaders("x-*-*").build())),
				equalTo("InvalidRequest")
			);
			assertThat(code(() -> putRules(client, CORSRule.builder().allowedMethods("GET").build())),
				equalTo("MalformedXML")
			);
			assertThat(code(() -> putRules(client, CORSRule.builder().allowedOrigins("*").build())),
				equalTo("MalformedXML")
			);
			assertThat(code(() -> putRules(client)), equalTo("MalformedXML"));
			assertThat(code(() -> putRules(client, IntStream.range(0, 101).mapToObj(number -> READS)
				.toArray(CORSRule[]::new))), equalTo("MalformedXML")
			);
			assertThat(noRules(client), equalTo("NoSuchCORSConfiguration"));
		}
	}
}
