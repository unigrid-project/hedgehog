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

import java.util.Arrays;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;

/* What x-amz-content-sha256 says about the body: its hash, or how it is framed and signed instead */
@RequiredArgsConstructor
enum PayloadMode {
	SHA256(null),
	UNSIGNED("UNSIGNED-PAYLOAD"),
	SIGNED_CHUNKS("STREAMING-AWS4-HMAC-SHA256-PAYLOAD"),
	SIGNED_CHUNKS_WITH_TRAILER("STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER"),
	UNSIGNED_CHUNKS_WITH_TRAILER("STREAMING-UNSIGNED-PAYLOAD-TRAILER");

	private static final Pattern HASH = Pattern.compile("\\p{XDigit}{64}");
	private static final String ECDSA = "STREAMING-AWS4-ECDSA-P256-SHA256-PAYLOAD";

	private final String value;

	static PayloadMode of(final String contentSha256) {
		if (HASH.matcher(contentSha256).matches()) {
			return SHA256;
		}

		if (contentSha256.startsWith(ECDSA)) {
			throw new S3Exception(S3ErrorCode.NotImplemented, "Signature Version 4A is not supported");
		}

		return Arrays.stream(values()).filter(mode -> contentSha256.equals(mode.value)).findFirst()
			.orElseThrow(() -> new S3Exception(S3ErrorCode.InvalidArgument, "x-amz-content-sha256 must be "
				+ "UNSIGNED-PAYLOAD, STREAMING-UNSIGNED-PAYLOAD-TRAILER, "
				+ "STREAMING-AWS4-HMAC-SHA256-PAYLOAD, STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER or a "
				+ "valid sha256 value."
			));
	}

	boolean isChunked() {
		return this != SHA256 && this != UNSIGNED;
	}

	boolean isSignedChunks() {
		return this == SIGNED_CHUNKS || this == SIGNED_CHUNKS_WITH_TRAILER;
	}

	boolean hasTrailer() {
		return this == SIGNED_CHUNKS_WITH_TRAILER || this == UNSIGNED_CHUNKS_WITH_TRAILER;
	}
}
