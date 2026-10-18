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

package org.unigrid.hedgehog.s3.auth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.jqwik.Expect;

public class AwsChunkedInputStreamTest {
	private static final String TIMESTAMP = "20130524T000000Z";
	private static final String SCOPE = "20130524/us-east-1/s3/aws4_request";
	private static final byte[] KEY = SigV4.signingKey("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "20130524",
		"us-east-1", "s3"
	);
	private static final String CRLF = "\r\n";
	private static final String TRAILER = "x-amz-checksum-crc32c";

	private static byte[] a(final int length) {
		final byte[] data = new byte[length];

		Arrays.fill(data, (byte) 'a');
		return data;
	}

	private static byte[] bytes(final Object... parts) {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();

		for (Object part : parts) {
			body.writeBytes(part instanceof byte[] data ? data : part.toString().getBytes(StandardCharsets.ISO_8859_1));
		}

		return body.toByteArray();
	}

	private static ChunkSigner signer(final String seed) {
		return new ChunkSigner(KEY, TIMESTAMP, SCOPE, seed);
	}

	private static byte[] decode(final AwsChunkedInputStream chunks) throws IOException {
		return chunks.readAllBytes();
	}

	private static S3ErrorCode refusal(final AwsChunkedInputStream chunks) {
		return Expect.assertThrows(S3Exception.class, chunks::readAllBytes).getCode();
	}

	/* The example of the S3 documentation: 64 KiB and 1 KiB of 'a', each in a chunk signed after the one before */
	private static byte[] documentedExample(final String finalSignature) {
		return bytes("10000;chunk-signature=ad80c730a21e5b8d04586a2213dd63b9a0e99e0e2307b0ade35a65485a288648", CRLF,
			a(65536), CRLF, "400;chunk-signature=0055627c9e194cb4542bae2aa5492e3c1575bbb81b612b7d234b86a503ef5497",
			CRLF, a(1024), CRLF, "0;chunk-signature=" + finalSignature, CRLF, CRLF
		);
	}

	private static byte[] documentedTrailerExample(final String checksum) {
		return bytes("10000;chunk-signature=b474d8862b1487a5145d686f57f013e54db672cee1c953b3010fb58501ef5aa2", CRLF,
			a(65536), CRLF, "400;chunk-signature=1c1344b170168f8e65b41376b44b20fe354e373826ccbbe2c1d40a8cae51e5c7",
			CRLF, a(1024), CRLF, "0;chunk-signature=2ca2aba2005185cf7159c6277faf83795951dd77a3a99e6e65d5c9f85863f992",
			CRLF, TRAILER + ":" + checksum, CRLF,
			"x-amz-trailer-signature:d81f82fc3505edab99d459891051a732e8730629a2e4a59689829ca17fe2e435", CRLF, CRLF
		);
	}

	@Example
	public void decodesTheSignedChunksOfTheS3Example() throws IOException {
		final byte[] body = documentedExample("b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9");

		assertThat(decode(new AwsChunkedInputStream(new ByteArrayInputStream(body),
			Optional.of(signer("4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9")), false)),
			equalTo(a(66560))
		);
	}

	@Example
	public void refusesATamperedChunk() {
		final byte[] body = documentedExample("b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9");

		body[1000] = 'b';
		assertThat(refusal(new AwsChunkedInputStream(new ByteArrayInputStream(body),
			Optional.of(signer("4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9")), false)),
			equalTo(S3ErrorCode.SignatureDoesNotMatch)
		);
	}

	@Example
	public void refusesChunksChainedFromAnotherSeed() {
		final byte[] body = documentedExample("b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9");

		assertThat(refusal(new AwsChunkedInputStream(new ByteArrayInputStream(body),
			Optional.of(signer("106e2a8a18243abcf37539882f36619c00e2dfc72633413f02d3b74544bfeb8e")), false)),
			equalTo(S3ErrorCode.SignatureDoesNotMatch)
		);
	}

	@Example
	public void refusesAWrongFinalSignature() {
		final byte[] body = documentedExample("0".repeat(64));

		assertThat(refusal(new AwsChunkedInputStream(new ByteArrayInputStream(body),
			Optional.of(signer("4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9")), false)),
			equalTo(S3ErrorCode.SignatureDoesNotMatch)
		);
	}

	@Example
	public void decodesTheSignedTrailerOfTheS3Example() throws IOException {
		final AwsChunkedInputStream chunks = new AwsChunkedInputStream(new ByteArrayInputStream(
			documentedTrailerExample("sOO8/Q==")),
			Optional.of(signer("106e2a8a18243abcf37539882f36619c00e2dfc72633413f02d3b74544bfeb8e")), true
		);

		assertThat(decode(chunks), equalTo(a(66560)));
		assertThat(chunks.trailer(TRAILER), equalTo(Optional.of("sOO8/Q==")));
	}

	@Example
	public void refusesATamperedTrailer() {
		assertThat(refusal(new AwsChunkedInputStream(new ByteArrayInputStream(documentedTrailerExample("AAAAAA==")),
			Optional.of(signer("106e2a8a18243abcf37539882f36619c00e2dfc72633413f02d3b74544bfeb8e")), true)),
			equalTo(S3ErrorCode.SignatureDoesNotMatch)
		);
	}

	private static byte[] unsigned(final byte[] data, final int size, final Map<String, String> trailers) {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();

		for (int offset = 0; offset < data.length; offset += size) {
			final int length = Math.min(size, data.length - offset);

			body.writeBytes(bytes(Integer.toHexString(length), CRLF));
			body.write(data, offset, length);
			body.writeBytes(bytes(CRLF));
		}

		body.writeBytes(bytes("0", CRLF, trailers.entrySet().stream().map(entry -> entry.getKey() + ":"
			+ entry.getValue() + CRLF).collect(Collectors.joining()), CRLF
		));
		return body.toByteArray();
	}

	private static String chunkSignature(final String previous, final byte[] chunk) {
		return SigV4.sign(KEY, String.join("\n", "AWS4-HMAC-SHA256-PAYLOAD", TIMESTAMP, SCOPE, previous,
			SigV4.sha256Hex(new byte[0]), SigV4.sha256Hex(chunk)
		));
	}

	private static byte[] signed(final byte[] data, final int size, final String seed) {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		String previous = seed;

		for (int offset = 0; offset < data.length; offset += size) {
			final byte[] chunk = Arrays.copyOfRange(data, offset, Math.min(offset + size, data.length));

			previous = chunkSignature(previous, chunk);
			body.writeBytes(bytes(Integer.toHexString(chunk.length) + ";chunk-signature=" + previous, CRLF, chunk,
				CRLF
			));
		}

		body.writeBytes(bytes("0;chunk-signature=" + chunkSignature(previous, new byte[0]), CRLF, CRLF));
		return body.toByteArray();
	}

	private static byte[] readInSteps(final AwsChunkedInputStream chunks, final List<Integer> steps)
		throws IOException {

		final ByteArrayOutputStream decoded = new ByteArrayOutputStream();
		final byte[] buffer = new byte[1024];

		for (int i = 0, read = 0; read >= 0; i++) {
			final int step = steps.isEmpty() ? buffer.length : steps.get(i % steps.size());

			read = step == 1 ? chunks.read() : chunks.read(buffer, 0, step);
			if (step == 1 && read >= 0) {
				decoded.write(read);
			} else if (read > 0) {
				decoded.write(buffer, 0, read);
			}
		}

		return decoded.toByteArray();
	}

	@Property
	public void decodesWhatWasChunkedWithoutSignatures(@ForAll @Size(max = 3000) byte[] data,
		@ForAll @IntRange(min = 1, max = 700) int size, @ForAll List<@IntRange(min = 1, max = 1024) Integer> steps)
		throws IOException {

		final Map<String, String> trailers = new LinkedHashMap<>(Map.of(TRAILER, "AAAAAA=="));
		final AwsChunkedInputStream chunks = new AwsChunkedInputStream(new ByteArrayInputStream(unsigned(data, size,
			trailers)), Optional.empty(), true
		);

		assertThat(readInSteps(chunks, steps), equalTo(data));
		assertThat(chunks.trailer(TRAILER), equalTo(Optional.of("AAAAAA==")));
		assertThat(chunks.read(), equalTo(-1));
	}

	@Property
	public void decodesWhatWasChunkedAndSigned(@ForAll @Size(max = 3000) byte[] data,
		@ForAll @IntRange(min = 1, max = 700) int size, @ForAll List<@IntRange(min = 1, max = 1024) Integer> steps)
		throws IOException {

		final String seed = SigV4.sha256Hex(data);
		final AwsChunkedInputStream chunks = new AwsChunkedInputStream(new ByteArrayInputStream(signed(data, size,
			seed)), Optional.of(signer(seed)), false
		);

		assertThat(readInSteps(chunks, steps), equalTo(data));
	}

	@Property
	public void refusesABodyCutShort(@ForAll @Size(min = 1, max = 2000) byte[] data,
		@ForAll @IntRange(min = 1, max = 700) int size, @ForAll @IntRange(min = 1, max = 100) int cut) {

		final byte[] body = unsigned(data, size, Map.of());
		final byte[] truncated = Arrays.copyOf(body, Math.max(0, body.length - 2 - cut));

		assertThat(refusal(new AwsChunkedInputStream(new ByteArrayInputStream(truncated), Optional.empty(), false)),
			equalTo(S3ErrorCode.IncompleteBody)
		);
	}

	@Example
	public void refusesMalformedFraming() {
		for (String body : new String[] { "zz\r\nab\r\n0\r\n\r\n", "2\r\nabX\r\n0\r\n\r\n",
			"1".repeat(5000) + "\r\n", "-1\r\n\r\n", "fffffffffffffffff\r\n" }) {

			assertThat(body.substring(0, 5), refusal(new AwsChunkedInputStream(new ByteArrayInputStream(bytes(body)),
				Optional.empty(), false)), equalTo(S3ErrorCode.InvalidRequest)
			);
		}
	}

	@Example
	public void refusesAnUnsignedChunkWhereSignaturesAreExpected() {
		assertThat(refusal(new AwsChunkedInputStream(new ByteArrayInputStream(bytes("1\r\na\r\n0\r\n\r\n")),
			Optional.of(signer("4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9")), false)),
			equalTo(S3ErrorCode.SignatureDoesNotMatch)
		);
	}
}
