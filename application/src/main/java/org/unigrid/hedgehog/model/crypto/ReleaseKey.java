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

package org.unigrid.hedgehog.model.crypto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Cleanup;
import lombok.NoArgsConstructor;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;

/**
* The Unigrid Foundation release key, the GPG key that signs every release asset. Its public half is
* the release-key.asc at the root of the repository, built into the jar unchanged.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ReleaseKey {
	private static final String RESOURCE = "/release-key.asc";
	private static final JcaPGPContentVerifierBuilderProvider VERIFIERS =
		new JcaPGPContentVerifierBuilderProvider().setProvider(new BouncyCastleProvider());

	public static PGPPublicKeyRingCollection getPublicKeyRing() {
		return Bundled.RING;
	}

	/* True only when one of the signatures in the armored block is a valid one by the release key. */
	public static boolean verify(byte[] data, byte[] armoredSignature) {
		try {
			final Object packet = new JcaPGPObjectFactory(PGPUtil.getDecoderStream(
				new ByteArrayInputStream(armoredSignature))).nextObject();

			if (!(packet instanceof PGPSignatureList signatures)) {
				return false;
			}

			for (final PGPSignature signature : signatures) {
				final PGPPublicKey key = getPublicKeyRing().getPublicKey(signature.getKeyID());

				if (Objects.nonNull(key)) {
					signature.init(VERIFIERS, key);
					signature.update(data);

					if (signature.verify()) {
						return true;
					}
				}
			}

			return false;

		} catch (IOException | PGPException ex) {
			return false;
		}
	}

	private static final class Bundled {
		private static final PGPPublicKeyRingCollection RING = load();

		private static PGPPublicKeyRingCollection load() {
			try {
				@Cleanup final InputStream stream = Objects.requireNonNull(
					ReleaseKey.class.getResourceAsStream(RESOURCE), "No " + RESOURCE + " in the build");

				return new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(stream),
					new JcaKeyFingerprintCalculator());

			} catch (IOException ex) {
				throw new UncheckedIOException(ex);

			} catch (PGPException ex) {
				throw new IllegalStateException("The bundled release key cannot be read", ex);
			}
		}
	}
}
