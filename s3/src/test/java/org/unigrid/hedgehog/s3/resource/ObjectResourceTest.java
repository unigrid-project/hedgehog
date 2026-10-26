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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.s3.auth.SdkSigner;
import org.unigrid.hedgehog.s3.auth.SigV4;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.s3.server.S3Fixture;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

public class ObjectResourceTest {
	static final String BUCKET = "photos";
	private static final byte[] DATA = new byte[70_000];
	private static final long TOO_LARGE = (5L << 30) + 1;
	private static final int TIMEOUT_MILLIS = 10_000;

	private static final List<String> DESCRIBING = List.of("ETag", "Last-Modified", "Content-Type",
		"Content-Disposition", "Content-Language", "Cache-Control", "Expires", "Accept-Ranges", "x-amz-meta-taken-by",
		"x-amz-meta-place");

	static {
		new Random(DATA.length).nextBytes(DATA);
	}

	static S3Node node() throws Exception {
		final S3Node node = S3Node.start();

		node.getClient().createBucket(request -> request.bucket(BUCKET));
		return node;
	}

	static String md5(final byte[] data) throws Exception {
		return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data)) + "\"";
	}

	static void put(final S3Client client, final String key, final byte[] data) {
		client.putObject(request -> request.bucket(BUCKET).key(key), RequestBody.fromBytes(data));
	}

	static ResponseBytes<GetObjectResponse> get(final S3Client client, final String key) {
		return client.getObjectAsBytes(request -> request.bucket(BUCKET).key(key));
	}

	static int status(final Runnable request) {
		return assertThrows(S3Exception.class, request::run).statusCode();
	}

	static String code(final Runnable request) {
		return assertThrows(S3Exception.class, request::run).awsErrorDetails().errorCode();
	}

	@Example
	public void keepsAnObjectWithItsHeadersAndMetadata() throws Exception {
		final Instant expires = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);

		try (S3Node node = node()) {
			final String etag = node.getClient().putObject(request -> request.bucket(BUCKET).key("a/b.jpg")
				.contentType("image/jpeg").contentDisposition("inline").contentLanguage("sv").cacheControl("no-cache")
				.expires(expires).metadata(Map.of("Taken-By", "alice", "place", "Visby")), RequestBody.fromBytes(DATA)
			).eTag();

			final ResponseBytes<GetObjectResponse> read = get(node.getClient(), "a/b.jpg");
			final GetObjectResponse response = read.response();
			final HeadObjectResponse head = node.getClient().headObject(request -> request.bucket(BUCKET)
				.key("a/b.jpg"));

			assertThat(etag, equalTo(md5(DATA)));
			assertThat(read.asByteArray(), equalTo(DATA));
			assertThat(response.eTag(), equalTo(etag));
			assertThat(response.contentType(), equalTo("image/jpeg"));
			assertThat(head.contentLength(), equalTo((long) DATA.length));
			assertThat(response.contentDisposition(), equalTo("inline"));
			assertThat(response.contentLanguage(), equalTo("sv"));
			assertThat(response.cacheControl(), equalTo("no-cache"));
			assertThat(response.expires(), equalTo(expires));
			assertThat(response.metadata(), equalTo(Map.of("taken-by", "alice", "place", "Visby")));
			assertThat(response.acceptRanges(), equalTo("bytes"));

			for (String header : DESCRIBING) {
				assertThat(head.sdkHttpResponse().firstMatchingHeader(header),
					equalTo(response.sdkHttpResponse().firstMatchingHeader(header))
				);
			}

			assertThat(head.lastModified().isAfter(Instant.now().minusSeconds(60)), equalTo(true));
			assertThat(head.versionId(), nullValue());
		}
	}

	@Example
	public void keepsAnEmptyObjectAndAnyKeyAClientMayName() throws Exception {
		try (S3Node node = node()) {
			for (String key : new String[] { "folder/", "a b+c/€ ~d%e?f=g&h.txt", "x".repeat(1024) }) {
				put(node.getClient(), key, DATA);
				assertThat(get(node.getClient(), key).asByteArray(), equalTo(DATA));
			}

			put(node.getClient(), "empty", new byte[0]);
			assertThat(get(node.getClient(), "empty").asByteArray(), equalTo(new byte[0]));
			assertThat(get(node.getClient(), "empty").response().contentLength(), equalTo(0L));
		}
	}

	@Example
	public void refusesAKeyLongerThanS3Allows() throws Exception {
		final String tooLong = "€".repeat(342);

		try (S3Node node = node()) {
			put(node.getClient(), "key", DATA);

			assertThat(code(() -> put(node.getClient(), tooLong, DATA)), equalTo("KeyTooLongError"));
			assertThat(code(() -> get(node.getClient(), tooLong)), equalTo("KeyTooLongError"));
			assertThat(code(() -> node.getClient().copyObject(request -> request.sourceBucket(BUCKET)
				.sourceKey(tooLong).destinationBucket(BUCKET).destinationKey("copy"))), equalTo("KeyTooLongError")
			);
			assertThat(code(() -> node.getClient().copyObject(request -> request.sourceBucket(BUCKET)
				.sourceKey("key").destinationBucket(BUCKET).destinationKey(tooLong))), equalTo("KeyTooLongError")
			);
		}
	}

	@Example
	public void answersNoSuchKeyAndNoSuchBucket() throws Exception {
		try (S3Node node = node()) {
			assertThrows(NoSuchKeyException.class, () -> get(node.getClient(), "missing"));
			assertThrows(NoSuchKeyException.class, () -> node.getClient().headObject(request -> request
				.bucket(BUCKET).key("missing"))
			);
			assertThrows(NoSuchBucketException.class, () -> node.getClient().putObject(request -> request
				.bucket("nowhere").key("key"), RequestBody.fromBytes(DATA))
			);
			assertThrows(NoSuchBucketException.class, () -> node.getClient().getObject(request -> request
				.bucket("nowhere").key("key"))
			);
		}
	}

	@Example
	public void readsOnlyWhenTheConditionsHold() throws Exception {
		try (S3Node node = node()) {
			put(node.getClient(), "key", DATA);

			final String etag = md5(DATA);
			final Instant hourAgo = Instant.now().minus(Duration.ofHours(1));
			final Instant inAnHour = Instant.now().plus(Duration.ofHours(1));

			assertThat(get(node.getClient(), "key").response().eTag(), equalTo(etag));
			assertThat(node.getClient().getObjectAsBytes(request -> request.bucket(BUCKET).key("key").ifMatch(etag)
				.ifModifiedSince(hourAgo)).asByteArray(), equalTo(DATA)
			);
			assertThat(status(() -> node.getClient().getObject(request -> request.bucket(BUCKET).key("key")
				.ifMatch("\"other\""))), equalTo(HttpURLConnection.HTTP_PRECON_FAILED)
			);
			assertThat(status(() -> node.getClient().getObject(request -> request.bucket(BUCKET).key("key")
				.ifUnmodifiedSince(hourAgo))), equalTo(HttpURLConnection.HTTP_PRECON_FAILED)
			);
			assertThat(status(() -> node.getClient().getObject(request -> request.bucket(BUCKET).key("key")
				.ifNoneMatch(etag))), equalTo(HttpURLConnection.HTTP_NOT_MODIFIED)
			);
			assertThat(status(() -> node.getClient().headObject(request -> request.bucket(BUCKET).key("key")
				.ifModifiedSince(inAnHour))), equalTo(HttpURLConnection.HTTP_NOT_MODIFIED)
			);
		}
	}

	@Example
	public void writesOnlyWhenTheConditionsHold() throws Exception {
		final String etag = md5(DATA);

		try (S3Node node = node()) {
			node.getClient().putObject(request -> request.bucket(BUCKET).key("key").ifNoneMatch("*"),
				RequestBody.fromBytes(DATA)
			);

			assertThat(code(() -> node.getClient().putObject(request -> request.bucket(BUCKET).key("key")
				.ifNoneMatch("*"), RequestBody.fromString("other"))), equalTo("PreconditionFailed")
			);
			assertThat(code(() -> node.getClient().putObject(request -> request.bucket(BUCKET).key("key")
				.ifMatch("\"other\""), RequestBody.fromString("other"))), equalTo("PreconditionFailed")
			);
			assertThat(code(() -> node.getClient().putObject(request -> request.bucket(BUCKET).key("missing")
				.ifMatch(etag), RequestBody.fromString("other"))), equalTo("NoSuchKey")
			);

			node.getClient().putObject(request -> request.bucket(BUCKET).key("key").ifMatch(etag),
				RequestBody.fromString("replaced")
			);

			assertThat(get(node.getClient(), "key").asUtf8String(), equalTo("replaced"));
			assertThat(node.getBlobs().size(), equalTo(2));
		}
	}

	@Example
	public void overridesResponseHeadersAsAsked() throws Exception {
		try (S3Node node = node()) {
			node.getClient().putObject(request -> request.bucket(BUCKET).key("key").contentType("image/png"),
				RequestBody.fromBytes(DATA)
			);

			final GetObjectResponse response = node.getClient().getObjectAsBytes(request -> request.bucket(BUCKET)
				.key("key").responseContentType("application/x-test").responseContentDisposition("attachment")
				.responseCacheControl("max-age=60")).response();

			assertThat(response.contentType(), equalTo("application/x-test"));
			assertThat(response.contentDisposition(), equalTo("attachment"));
			assertThat(response.cacheControl(), equalTo("max-age=60"));
		}
	}

	@Example
	public void keepsTheChecksumTheClientSent() throws Exception {
		try (S3Node node = node()) {
			final String checksum = node.getClient().putObject(request -> request.bucket(BUCKET).key("key")
				.checksumAlgorithm(ChecksumAlgorithm.SHA256), RequestBody.fromBytes(DATA)).checksumSHA256();

			assertThat(node.getClient().getObjectAsBytes(request -> request.bucket(BUCKET).key("key")
				.checksumMode(ChecksumMode.ENABLED)).response().checksumSHA256(), equalTo(checksum)
			);
			assertThat(checksum, equalTo(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
				.digest(DATA)))
			);
		}
	}

	@Example
	public void refusesWhatItDoesNotImplement() throws Exception {
		try (S3Node node = node()) {
			assertThat(code(() -> node.getClient().putObject(request -> request.bucket(BUCKET).key("key")
				.storageClass(StorageClass.GLACIER), RequestBody.fromBytes(DATA))), equalTo("NotImplemented")
			);
			assertThat(code(() -> node.getClient().putObject(request -> request.bucket(BUCKET).key("key")
				.serverSideEncryption(ServerSideEncryption.AWS_KMS), RequestBody.fromBytes(DATA))),
				equalTo("NotImplemented")
			);
			assertThat(code(() -> node.getClient().getObjectAcl(request -> request.bucket(BUCKET).key("key"))),
				equalTo("NotImplemented")
			);
		}
	}

	/* Refused from its declared length alone, before any of it is sent */
	@Example
	public void refusesAnObjectLargerThanASinglePutAllows() throws Exception {
		try (S3Node node = node()) {
			final SdkHttpRequest signed = SdkSigner.sign(SdkHttpRequest.builder().method(SdkHttpMethod.PUT)
				.uri(S3Fixture.endpoint(node.getPort()).resolve("/" + BUCKET + "/huge"))
				.putHeader("x-amz-content-sha256", SigV4.sha256Hex(new byte[0])).build(), new byte[0],
				S3Fixture.ACCESS_KEY, S3Fixture.SECRET_KEY, Clock.systemUTC()
			);
			final String headers = signed.headers().entrySet().stream().map(header -> header.getKey() + ": "
				+ header.getValue().getFirst() + "\r\n").collect(Collectors.joining());

			try (Socket socket = new Socket(S3Fixture.HOST, node.getPort())) {
				final OutputStream out = socket.getOutputStream();
				final InputStream in = socket.getInputStream();

				socket.setSoTimeout(TIMEOUT_MILLIS);
				out.write(("PUT /" + BUCKET + "/huge HTTP/1.1\r\n" + headers + "Content-Length: " + TOO_LARGE
					+ "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
				out.flush();

				assertThat(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1),
					matchesPattern("(?s)HTTP/1\\.1 400 .*<Code>EntityTooLarge</Code>.*")
				);
			}
		}
	}

	@Example
	public void servesPresignedPutsAndGets() throws Exception {
		try (S3Node node = node(); S3Presigner presigner = S3Presigner.builder()
			.endpointOverride(S3Fixture.endpoint(node.getPort())).region(Region.US_EAST_1)
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(S3Fixture.ACCESS_KEY,
			S3Fixture.SECRET_KEY))).build()) {

			final URL put = presigner.presignPutObject(request -> request.signatureDuration(Duration.ofMinutes(5))
				.putObjectRequest(object -> object.bucket(BUCKET).key("shared/a b.bin"))).url();
			final HttpURLConnection upload = (HttpURLConnection) put.openConnection();

			upload.setRequestMethod("PUT");
			upload.setDoOutput(true);
			upload.getOutputStream().write(DATA);
			assertThat(upload.getResponseCode(), equalTo(HttpURLConnection.HTTP_OK));
			assertThat(upload.getHeaderField("ETag"), equalTo(md5(DATA)));

			final URL get = presigner.presignGetObject(request -> request.signatureDuration(Duration.ofMinutes(5))
				.getObjectRequest(object -> object.bucket(BUCKET).key("shared/a b.bin"))).url();

			try (InputStream in = get.openStream()) {
				assertThat(in.readAllBytes(), equalTo(DATA));
			}
		}
	}
}
