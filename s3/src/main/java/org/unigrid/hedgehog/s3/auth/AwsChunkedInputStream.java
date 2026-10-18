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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.checksum.ChecksumAlgorithm;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;

/*
   Decodes an aws-chunked body: chunks of '<hex size>[;chunk-signature=<signature>]' and data, a last chunk of size
   zero, and then trailing headers when the client announced them. A chunk is verified as its last byte is read, so
   a reader never sees the end of a chunk the signature refuses.
*/
@RequiredArgsConstructor
final class AwsChunkedInputStream extends InputStream {
	private static final int MAX_LINE = 4096;
	private static final Pattern HEADER = Pattern.compile("(\\p{XDigit}{1,15})(?:;chunk-signature=(\\p{XDigit}+))?"
		+ "(?:;.*)?"
	);
	private static final String TRAILER_SIGNATURE = "x-amz-trailer-signature";

	private final InputStream input;
	private final Optional<ChunkSigner> signer;
	private final boolean trailing;
	private final Map<String, String> trailers = new LinkedHashMap<>();

	private final MessageDigest digest = ChecksumAlgorithm.SHA256.digest();
	private long remaining;
	private String signature;
	private boolean started;
	private boolean ended;

	private static S3Exception malformed(final String message) {
		return new S3Exception(S3ErrorCode.InvalidRequest, message);
	}

	private static S3Exception truncated() {
		return new S3Exception(S3ErrorCode.IncompleteBody);
	}

	public Optional<String> trailer(final String name) {
		return Optional.ofNullable(trailers.get(name.toLowerCase(Locale.ROOT)));
	}

	@Override
	public int read() throws IOException {
		final byte[] single = new byte[1];

		return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
	}

	@Override
	public int read(final byte[] buffer, final int offset, final int length) throws IOException {
		if (remaining == 0 && !ended) {
			nextChunk();
		}

		if (ended) {
			return -1;
		}

		final int read = input.read(buffer, offset, (int) Math.min(length, remaining));

		if (read < 0) {
			throw truncated();
		}

		remaining -= read;
		digest.update(buffer, offset, read);

		if (remaining == 0) {
			signer.ifPresent(chunks -> chunks.verifyChunk(digest.digest(), signature));
		}

		return read;
	}

	private void nextChunk() throws IOException {
		if (started && !readLine().orElseThrow(AwsChunkedInputStream::truncated).isEmpty()) {
			throw malformed("A chunk does not end where its size says");
		}

		final Matcher header = HEADER.matcher(readLine().orElseThrow(AwsChunkedInputStream::truncated));

		if (!header.matches()) {
			throw malformed("A chunk does not start with its size");
		}

		started = true;
		remaining = Long.parseLong(header.group(1), 16);
		signature = header.group(2);

		if (remaining == 0) {
			signer.ifPresent(chunks -> chunks.verifyChunk(digest.digest(), signature));
			readTrailers();
			ended = true;
		}
	}

	private void readTrailers() throws IOException {
		for (Optional<String> line = readLine(); line.isPresent() && !line.get().isEmpty(); line = readLine()) {
			final String[] trailer = line.get().split(":", 2);

			if (!trailing || trailer.length != 2) {
				throw malformed("The body ends with something other than the trailers it announced");
			}

			trailers.put(trailer[0].strip().toLowerCase(Locale.ROOT), trailer[1].strip());
		}

		if (trailing) {
			final String canonical = trailers.entrySet().stream()
				.filter(trailer -> !TRAILER_SIGNATURE.equals(trailer.getKey()))
				.map(trailer -> trailer.getKey() + ":" + trailer.getValue() + "\n")
				.collect(Collectors.joining());

			signer.ifPresent(chunks -> chunks.verifyTrailer(canonical, trailers.get(TRAILER_SIGNATURE)));
		}
	}

	/* A line ends with CRLF, and a body may end right after the trailers without the empty line closing them */
	private Optional<String> readLine() throws IOException {
		final ByteArrayOutputStream line = new ByteArrayOutputStream();
		int octet = input.read();

		if (octet < 0) {
			return Optional.empty();
		}

		for (; octet != '\n'; octet = input.read()) {
			if (octet < 0) {
				throw truncated();
			}

			if (line.size() == MAX_LINE) {
				throw malformed("A chunk header or trailer is too long");
			}

			line.write(octet);
		}

		final String text = line.toString(StandardCharsets.ISO_8859_1);

		if (!text.endsWith("\r")) {
			throw malformed("A line of the body does not end with CRLF");
		}

		return Optional.of(text.substring(0, text.length() - 1));
	}
}
