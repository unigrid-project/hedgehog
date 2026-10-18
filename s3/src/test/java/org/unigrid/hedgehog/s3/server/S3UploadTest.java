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

import java.net.URI;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;
import java.util.function.Consumer;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.s3.checksum.ChecksumAlgorithm;
import org.unigrid.hedgehog.s3.jqwik.Expect;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/* Large enough that clients split it into several chunks */
public class S3UploadTest {
	private static final byte[] DATA = new byte[300_000];

	static {
		new Random(DATA.length).nextBytes(DATA);
	}

	private static String md5() throws Exception {
		return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(DATA)) + "\"";
	}

	private static String crc32() {
		return Base64.getEncoder().encodeToString(ChecksumAlgorithm.CRC32.digest().digest(DATA));
	}

	private static PutObjectResponse put(final Consumer<S3ClientBuilder> setup,
		final Consumer<PutObjectRequest.Builder> request) throws Exception {

		final int port = S3Fixture.freePort();
		final S3ClientBuilder builder = S3Fixture.client(S3Fixture.endpoint(port), S3Fixture.SECRET_KEY);

		setup.accept(builder);

		try (S3Server server = S3Fixture.start(port, EchoResource.class); S3Client client = builder.build()) {
			return client.putObject(put -> request.accept(put.bucket("bucket").key("data.bin")),
				RequestBody.fromBytes(DATA)
			);
		}
	}

	private static String echoed(final PutObjectResponse response, final String header) {
		return response.sdkHttpResponse().firstMatchingHeader(header).orElseThrow();
	}

	private static void assertDecoded(final PutObjectResponse response) throws Exception {
		assertThat(response.eTag(), equalTo(md5()));
		assertThat(echoed(response, EchoResource.DECODED), equalTo(DATA.length + " " + DATA.length + " null"));
	}

	@Example
	public void takesSignedChunksWithATrailingChecksum() throws Exception {
		final PutObjectResponse response = put(builder -> { }, request -> { });

		assertDecoded(response);
		assertThat(echoed(response, EchoResource.CONTENT_SHA256), equalTo("STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER"));
		assertThat(response.checksumCRC32(), equalTo(crc32()));
	}

	@Example
	public void takesSignedChunksAlone() throws Exception {
		final PutObjectResponse response = put(builder -> builder
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED), request -> { }
		);

		assertDecoded(response);
		assertThat(echoed(response, EchoResource.CONTENT_SHA256), equalTo("STREAMING-AWS4-HMAC-SHA256-PAYLOAD"));
		assertThat(response.checksumCRC32(), nullValue());
	}

	@Example
	public void takesABodySignedByItsHash() throws Exception {
		final PutObjectResponse response = put(builder -> builder
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build()), request -> { }
		);

		assertDecoded(response);
		assertThat(echoed(response, EchoResource.CONTENT_SHA256),
			equalTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(DATA)))
		);
	}

	@Example
	public void takesUnsignedChunksWithATrailingChecksumOverTls() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.startTls(port, EchoResource.class);
			SdkHttpClient http = S3Fixture.trustingHttpClient();
			S3Client client = S3Fixture.client(URI.create("https://" + S3Fixture.HOST + ":" + port),
				S3Fixture.SECRET_KEY).httpClient(http).build()) {

			final PutObjectResponse response = client.putObject(put -> put.bucket("bucket").key("data.bin"),
				RequestBody.fromBytes(DATA)
			);

			assertDecoded(response);
			assertThat(echoed(response, EchoResource.CONTENT_SHA256), equalTo("STREAMING-UNSIGNED-PAYLOAD-TRAILER"));
			assertThat(response.checksumCRC32(), equalTo(crc32()));
		}
	}

	@Example
	public void refusesABodyThatDoesNotMatchItsChecksum() {
		assertThat(Expect.assertThrows(S3Exception.class, () -> put(builder -> { },
			request -> request.checksumCRC32("AAAAAA=="))).awsErrorDetails().errorCode(), equalTo("BadDigest")
		);
	}

	@Example
	public void refusesABodyThatDoesNotMatchItsMd5() {
		assertThat(Expect.assertThrows(S3Exception.class, () -> put(builder -> builder
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED),
			request -> request.contentMD5("AAAAAAAAAAAAAAAAAAAAAA=="))).awsErrorDetails().errorCode(),
			equalTo("BadDigest")
		);
	}
}
