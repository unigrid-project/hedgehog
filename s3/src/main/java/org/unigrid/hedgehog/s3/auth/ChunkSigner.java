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

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import lombok.AllArgsConstructor;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;

/*
   Each chunk of a signed aws-chunked body is signed together with the signature before it, starting from the
   signature of the request, so no chunk can be dropped, reordered or replaced.
*/
@AllArgsConstructor
final class ChunkSigner {
	private static final String CHUNK = SigV4.ALGORITHM + "-PAYLOAD";
	private static final String TRAILER = SigV4.ALGORITHM + "-TRAILER";
	private static final String EMPTY_SHA256 = SigV4.sha256Hex(new byte[0]);

	private final byte[] signingKey;
	private final String timestamp;
	private final String scope;
	private String previous;

	void verifyChunk(final byte[] dataSha256, final String provided) {
		verify(String.join("\n", CHUNK, timestamp, scope, previous, EMPTY_SHA256,
			HexFormat.of().formatHex(dataSha256)), provided
		);
	}

	void verifyTrailer(final String canonicalTrailers, final String provided) {
		verify(String.join("\n", TRAILER, timestamp, scope, previous,
			SigV4.sha256Hex(canonicalTrailers.getBytes(StandardCharsets.UTF_8))), provided
		);
	}

	private void verify(final String stringToSign, final String provided) {
		final String expected = SigV4.sign(signingKey, stringToSign);

		if (provided == null || !SigV4.matches(expected, provided)) {
			throw new S3Exception(S3ErrorCode.SignatureDoesNotMatch);
		}

		previous = expected;
	}
}
