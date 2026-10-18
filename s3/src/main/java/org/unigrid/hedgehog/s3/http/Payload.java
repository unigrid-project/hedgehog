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

import jakarta.ws.rs.container.ContainerRequestContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Getter;
import org.unigrid.hedgehog.s3.checksum.ChecksumAlgorithm;
import org.unigrid.hedgehog.s3.checksum.ChecksumInputStream;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;

/*
   The body of an authenticated request, decoded and checked against every digest the client declared: its length,
   the signed SHA-256, Content-MD5 and an x-amz-checksum header or trailer. Resources read the request entity as
   usual, then call finish() before they keep what they read.
*/
public final class Payload {
	public static final String CONTENT_MD5 = "Content-MD5";
	public static final String TRAILER = "x-amz-trailer";
	public static final String SDK_CHECKSUM_ALGORITHM = "x-amz-sdk-checksum-algorithm";

	private static final String PROPERTY = Payload.class.getName();
	private static final int MD5_BYTES = 16;

	private final ChecksumInputStream stream;
	@Getter private final OptionalLong length;
	@Getter private final Optional<ChecksumAlgorithm> algorithm;
	private final Optional<byte[]> sha256;
	private final Optional<byte[]> md5;
	private final Supplier<Optional<String>> checksum;

	private Payload(final InputStream body, final OptionalLong length, final Optional<ChecksumAlgorithm> algorithm,
		final Optional<byte[]> sha256, final Optional<byte[]> md5, final Supplier<Optional<String>> checksum) {

		final Set<ChecksumAlgorithm> algorithms = EnumSet.noneOf(ChecksumAlgorithm.class);

		algorithm.ifPresent(algorithms::add);
		sha256.ifPresent(hash -> algorithms.add(ChecksumAlgorithm.SHA256));

		this.length = length;
		this.algorithm = algorithm;
		this.sha256 = sha256;
		this.md5 = md5;
		this.checksum = checksum;
		this.stream = new ChecksumInputStream(body, algorithms, this::verify);
	}

	public static Payload create(final Function<String, String> headers, final InputStream body,
		final OptionalLong length, final Optional<String> sha256,
		final Function<String, Optional<String>> trailers) {

		final Optional<String> declared = declaredChecksum(headers);
		final Optional<ChecksumAlgorithm> algorithm = declared.flatMap(ChecksumAlgorithm::ofHeader)
			.or(() -> Optional.ofNullable(headers.apply(TRAILER)).flatMap(ChecksumAlgorithm::ofHeader))
			.or(() -> Optional.ofNullable(headers.apply(SDK_CHECKSUM_ALGORITHM)).map(Payload::algorithm));

		algorithm.ifPresent(name -> declared.map(headers).ifPresent(value -> decode(name, value)));

		return new Payload(body, length, algorithm, sha256.map(HexFormat.of()::parseHex),
			Optional.ofNullable(headers.apply(CONTENT_MD5)).map(Payload::md5),
			() -> declared.map(headers).or(() -> algorithm.flatMap(name -> trailers.apply(name.getHeader())))
		);
	}

	public static Payload of(final ContainerRequestContext request) {
		return (Payload) request.getProperty(PROPERTY);
	}

	public void attach(final ContainerRequestContext request) {
		request.setEntityStream(stream);
		request.setProperty(PROPERTY, this);
	}

	private static Optional<String> declaredChecksum(final Function<String, String> headers) {
		final List<String> declared = Arrays.stream(ChecksumAlgorithm.values()).map(ChecksumAlgorithm::getHeader)
			.filter(header -> headers.apply(header) != null).toList();

		if (declared.size() > 1) {
			throw new S3Exception(S3ErrorCode.InvalidRequest, "Expecting a single x-amz-checksum- header");
		}

		return declared.stream().findFirst();
	}

	private static ChecksumAlgorithm algorithm(final String name) {
		return ChecksumAlgorithm.of(name).orElseThrow(() -> new S3Exception(S3ErrorCode.InvalidRequest,
			"Checksum algorithm provided is unsupported"
		));
	}

	private static byte[] decode(final ChecksumAlgorithm algorithm, final String value) {
		final S3Exception invalid = new S3Exception(S3ErrorCode.InvalidRequest, "Value for "
			+ algorithm.getHeader() + " header is invalid."
		);

		try {
			final byte[] decoded = Base64.getDecoder().decode(value);

			if (decoded.length != algorithm.digest().getDigestLength()) {
				throw invalid;
			}

			return decoded;
		} catch (IllegalArgumentException ex) {
			throw invalid;
		}
	}

	private static byte[] md5(final String value) {
		try {
			final byte[] decoded = Base64.getDecoder().decode(value);

			if (decoded.length == MD5_BYTES) {
				return decoded;
			}
		} catch (IllegalArgumentException ex) {
			/* Answered as an invalid digest below, like one of the wrong length */
		}

		throw new S3Exception(S3ErrorCode.InvalidDigest);
	}

	private static void refuseUnless(final boolean holds, final S3ErrorCode code) {
		if (!holds) {
			throw new S3Exception(code);
		}
	}

	private void verify(final ChecksumInputStream ended) {
		refuseUnless(length.isEmpty() || length.getAsLong() == ended.count(), S3ErrorCode.IncompleteBody);
		refuseUnless(sha256.map(hash -> Arrays.equals(hash, ended.digest(ChecksumAlgorithm.SHA256))).orElse(true),
			S3ErrorCode.XAmzContentSHA256Mismatch
		);
		refuseUnless(md5.map(hash -> Arrays.equals(hash, ended.md5())).orElse(true), S3ErrorCode.BadDigest);
		refuseUnless(algorithm.flatMap(name -> checksum.get().map(value -> Arrays.equals(decode(name, value),
			ended.digest(name)))).orElse(true), S3ErrorCode.BadDigest
		);
	}

	/* Reads whatever the resource left unread, so every check has run once this returns */
	public void finish() throws IOException {
		stream.transferTo(OutputStream.nullOutputStream());
	}

	public String getETag() {
		return HexFormat.of().formatHex(stream.md5());
	}

	public Optional<String> getChecksum() {
		return algorithm.map(name -> Base64.getEncoder().encodeToString(stream.digest(name)));
	}
}
