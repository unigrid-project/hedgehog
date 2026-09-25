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

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.unigrid.hedgehog.model.crypto.NetworkKey;

public record SignatureLogInfo(List<Entry> entries, Head head) {
	public record Entry(@JsonFormat(shape = JsonFormat.Shape.STRING) Instant timeStamp, String signer, String cosigner,
		String digest, String signature, String cosignature) {
	}

	/* A signer is null when no known network key made that signature, or when there is no such signature */
	public record Head(@JsonFormat(shape = JsonFormat.Shape.STRING) Instant timeStamp, String signer, String cosigner) {
	}

	public static SignatureLogInfo of(GridSpork spork) {
		final List<Entry> entries = spork.getSignatureLog().getEntries().stream()
			.map(entry -> new Entry(entry.getTimeStamp(), entry.getSigner(), entry.getCosigner(),
				Hex.encodeHexString(entry.getDigest()), Hex.encodeHexString(entry.getSignature()),
				hexOrNull(entry.getCosignature()))
			).toList();
		final byte[] digest = DigestUtils.sha512(spork.getSignable());
		final Head head = new Head(spork.getTimeStamp(), signerOf(digest, spork.getSignature()),
			signerOf(digest, spork.getCosignature())
		);

		return new SignatureLogInfo(entries, head);
	}

	private static String signerOf(byte[] digest, byte[] signature) {
		return Objects.isNull(signature) ? null : NetworkKey.signerOf(digest, signature).orElse(null);
	}

	private static String hexOrNull(byte[] bytes) {
		return Objects.isNull(bytes) ? null : Hex.encodeHexString(bytes);
	}
}
