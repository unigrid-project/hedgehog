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

package org.unigrid.hedgehog.proxy.session;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/* The AES-GCM cipher of the session code: 128-bit tag, no padding, one nonce and one associated data per use.
   The nonce layout and the key are the caller's, as they differ between the sealing of a session and the
   handshake. */
final class AesGcm {
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	static final int TAG_SIZE = 16;

	private AesGcm() {
	}

	/* A fresh cipher in the given mode, with the associated data already fed in */
	static Cipher cipher(int mode, SecretKey key, byte[] nonce, byte[] associatedData)
		throws GeneralSecurityException {
		final Cipher cipher = Cipher.getInstance(TRANSFORMATION);

		cipher.init(mode, key, new GCMParameterSpec(TAG_SIZE * Byte.SIZE, nonce));
		cipher.updateAAD(associatedData);
		return cipher;
	}

	/* For a failure that is not a bad tag: the platform has no AES-GCM or rejects the key or nonce */
	static IllegalStateException unavailable(GeneralSecurityException cause) {
		return new IllegalStateException("AES-GCM is mandatory on every Java platform", cause);
	}
}
