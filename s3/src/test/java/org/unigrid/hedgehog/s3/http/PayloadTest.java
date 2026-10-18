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

package org.unigrid.hedgehog.s3.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.checksum.ChecksumAlgorithm;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.jqwik.Expect;

public class PayloadTest {
	private static final byte[] BODY = "Welcome to Amazon S3.".getBytes();

	private static String base64(final byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	private static byte[] flipped(final byte[] bytes) {
		final byte[] copy = bytes.clone();

		copy[0] ^= 1;
		return copy;
	}

	private static Payload payload(final byte[] body, final Map<String, String> headers) {
		return payload(body, headers, Map.of(), Optional.empty());
	}

	private static Payload payload(final byte[] body, final Map<String, String> headers,
		final Map<String, String> trailers, final Optional<String> sha256) {

		return Payload.create(headers::get, new ByteArrayInputStream(body), OptionalLong.of(body.length), sha256,
			name -> Optional.ofNullable(trailers.get(name))
		);
	}

	private static S3ErrorCode refusal(final Payload payload) {
		return Expect.assertThrows(S3Exception.class, payload::finish).getCode();
	}

	@Property
	public void keepsTheChecksumItWasAskedToVerify(@ForAll byte[] body, @ForAll ChecksumAlgorithm algorithm)
		throws Exception {

		final String checksum = base64(algorithm.digest().digest(body));
		final Payload payload = payload(body, Map.of(algorithm.getHeader(), checksum));

		payload.finish();
		assertThat(payload.getAlgorithm(), equalTo(Optional.of(algorithm)));
		assertThat(payload.getChecksum(), equalTo(Optional.of(checksum)));
		assertThat(payload.getETag(), equalTo(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(body))));
	}

	@Property
	public void refusesABodyThatDoesNotMatchItsChecksum(@ForAll byte[] body, @ForAll ChecksumAlgorithm algorithm) {
		final Payload payload = payload(body, Map.of(algorithm.getHeader(),
			base64(flipped(algorithm.digest().digest(body)))
		));

		assertThat(refusal(payload), equalTo(S3ErrorCode.BadDigest));
	}

	@Property
	public void verifiesAChecksumThatTrailsTheBody(@ForAll byte[] body, @ForAll ChecksumAlgorithm algorithm,
		@ForAll boolean intact) throws IOException {

		final byte[] digest = algorithm.digest().digest(body);
		final Payload payload = payload(body, Map.of("x-amz-trailer", algorithm.getHeader()),
			Map.of(algorithm.getHeader(), base64(intact ? digest : flipped(digest))), Optional.empty()
		);

		if (intact) {
			payload.finish();
			assertThat(payload.getChecksum(), equalTo(Optional.of(base64(digest))));
		} else {
			assertThat(refusal(payload), equalTo(S3ErrorCode.BadDigest));
		}
	}

	@Property
	public void verifiesContentMd5(@ForAll byte[] body, @ForAll boolean intact) throws Exception {
		final byte[] md5 = MessageDigest.getInstance("MD5").digest(body);
		final Payload payload = payload(body, Map.of("Content-MD5", base64(intact ? md5 : flipped(md5))));

		if (intact) {
			payload.finish();
		} else {
			assertThat(refusal(payload), equalTo(S3ErrorCode.BadDigest));
		}
	}

	@Property
	public void verifiesTheSignedPayloadHash(@ForAll byte[] body, @ForAll boolean intact) throws Exception {
		final byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(body);
		final Payload payload = payload(body, Map.of(), Map.of(),
			Optional.of(HexFormat.of().formatHex(intact ? sha256 : flipped(sha256)))
		);

		if (intact) {
			payload.finish();
		} else {
			assertThat(refusal(payload), equalTo(S3ErrorCode.XAmzContentSHA256Mismatch));
		}
	}

	@Example
	public void refusesABodyOfAnotherLengthThanDeclared() {
		final Payload payload = Payload.create(name -> null, new ByteArrayInputStream(BODY),
			OptionalLong.of(BODY.length + 1), Optional.empty(), name -> Optional.empty()
		);

		assertThat(refusal(payload), equalTo(S3ErrorCode.IncompleteBody));
	}

	@Example
	public void computesTheChecksumOfANamedAlgorithmWithoutAValue() throws IOException {
		final Payload payload = payload(BODY, Map.of("x-amz-sdk-checksum-algorithm", "crc32c"));

		payload.finish();
		assertThat(payload.getChecksum(), equalTo(Optional.of(base64(ChecksumAlgorithm.CRC32C.digest().digest(BODY)))));
	}

	@Example
	public void refusesDeclaredDigestsItCannotRead() {
		final Map<String, String> twoChecksums = new HashMap<>(Map.of("x-amz-checksum-crc32", "AAAAAA==",
			"x-amz-checksum-sha1", base64(new byte[20])
		));

		assertThat(Expect.assertThrows(S3Exception.class, () -> payload(BODY, twoChecksums)).getCode(),
			equalTo(S3ErrorCode.InvalidRequest)
		);
		assertThat(Expect.assertThrows(S3Exception.class, () -> payload(BODY, Map.of("x-amz-checksum-crc32",
			"AAAA"))).getCode(), equalTo(S3ErrorCode.InvalidRequest)
		);
		assertThat(Expect.assertThrows(S3Exception.class, () -> payload(BODY, Map.of("x-amz-sdk-checksum-algorithm",
			"MD4"))).getCode(), equalTo(S3ErrorCode.InvalidRequest)
		);
		assertThat(Expect.assertThrows(S3Exception.class, () -> payload(BODY, Map.of("Content-MD5", "not md5")))
			.getCode(), equalTo(S3ErrorCode.InvalidDigest)
		);
	}

	@Example
	public void refusesATrailingChecksumItCannotRead() {
		final Payload payload = payload(BODY, Map.of("x-amz-trailer", "x-amz-checksum-crc32"),
			Map.of("x-amz-checksum-crc32", "@@@@"), Optional.empty()
		);

		assertThat(refusal(payload), equalTo(S3ErrorCode.InvalidRequest));
	}
}
