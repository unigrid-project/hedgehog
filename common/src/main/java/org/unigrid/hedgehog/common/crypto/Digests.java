/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.common.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Digests {
	private static final String SHA_256 = "SHA-256";

	private Digests() {
		/* Static helpers only */
	}

	/* A fresh digest, for callers that keep one of their own and feed it incrementally */
	public static MessageDigest newSha256() {
		try {
			return MessageDigest.getInstance(SHA_256);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is mandatory on every Java platform", ex);
		}
	}

	/* The digest of the parts taken one after the other, as if they were a single array */
	public static byte[] sha256(byte[]... parts) {
		final MessageDigest digest = newSha256();

		for (byte[] part : parts) {
			digest.update(part);
		}

		return digest.digest();
	}
}
