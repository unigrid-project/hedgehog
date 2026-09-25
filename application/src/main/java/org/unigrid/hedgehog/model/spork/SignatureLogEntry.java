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

package org.unigrid.hedgehog.model.spork;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.VerifySignatureException;

@Slf4j
@Value
@Builder(toBuilder = true)
public class SignatureLogEntry implements Serializable {
	private static final long serialVersionUID = 1L;

	public static final int DIGEST_SIZE = 64;

	private Instant timeStamp;
	private String signer;
	private byte[] digest;
	private byte[] signature;

	public byte[] toBytes() {
		final byte[] signerBytes = signer.getBytes(StandardCharsets.US_ASCII);

		return ByteBuffer.allocate(Long.BYTES + Short.BYTES + signerBytes.length + DIGEST_SIZE
			+ Short.BYTES + signature.length)
			.putLong(timeStamp.toEpochMilli())
			.putShort((short) signerBytes.length).put(signerBytes)
			.put(digest)
			.putShort((short) signature.length).put(signature)
			.array();
	}

	public boolean isValid() {
		try {
			return digest.length == DIGEST_SIZE && Signature.verifyDigest(digest, signature, signer);
		} catch (VerifySignatureException | IllegalArgumentException ex) {
			log.atTrace().log("Log entry signed at {} does not verify: {}", timeStamp, ex.getMessage());
			return false;
		}
	}
}
