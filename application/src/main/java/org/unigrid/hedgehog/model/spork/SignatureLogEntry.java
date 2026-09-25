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
import java.util.List;
import java.util.Objects;
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
	private String cosigner;
	private byte[] cosignature;

	public boolean isCosigned() {
		return Objects.nonNull(cosigner) && Objects.nonNull(cosignature);
	}

	public List<String> getSigners() {
		return isCosigned() ? List.of(signer, cosigner) : List.of(signer);
	}

	public byte[] toBytes() {
		final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES + DIGEST_SIZE + sizeOf(signer, signature)
			+ (isCosigned() ? sizeOf(cosigner, cosignature) : 0));

		buffer.putLong(timeStamp.toEpochMilli());
		putSized(buffer, signer.getBytes(StandardCharsets.US_ASCII));
		buffer.put(digest);
		putSized(buffer, signature);

		/* Nothing is added without a cosigner, so entries logged before co-signing keep their hash */
		if (isCosigned()) {
			putSized(buffer, cosigner.getBytes(StandardCharsets.US_ASCII));
			putSized(buffer, cosignature);
		}

		return buffer.array();
	}

	private static int sizeOf(String key, byte[] keySignature) {
		return 2 * Short.BYTES + key.length() + keySignature.length;
	}

	private static void putSized(ByteBuffer buffer, byte[] bytes) {
		buffer.putShort((short) bytes.length).put(bytes);
	}

	public boolean isValid() {
		return digest.length == DIGEST_SIZE && verifies(signer, signature)
			&& (!isCosigned() || !cosigner.equals(signer) && verifies(cosigner, cosignature));
	}

	private boolean verifies(String key, byte[] keySignature) {
		try {
			return Signature.verifyDigest(digest, keySignature, key);
		} catch (VerifySignatureException | IllegalArgumentException ex) {
			log.atTrace().log("Log entry signed at {} does not verify: {}", timeStamp, ex.getMessage());
			return false;
		}
	}
}
