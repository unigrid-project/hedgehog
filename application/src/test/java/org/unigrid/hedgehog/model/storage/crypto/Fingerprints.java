/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.storage.crypto;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ArbitrarySupplier;
import org.bitcoinj.core.Base58;
import org.unigrid.hedgehog.model.storage.StorageFormat;

final class Fingerprints {
	private Fingerprints() {
		/* Static helpers only */
	}

	static Fingerprint of(byte[] secret) {
		return Fingerprint.parse(Base58.encodeChecked(StorageFormat.current().getId() & 0xFF, secret));
	}

	static byte[] knownSecret() {
		final byte[] secret = new byte[Fingerprint.SECRET_SIZE];

		for (int i = 0; i < secret.length; i++) {
			secret[i] = (byte) i;
		}

		return secret;
	}

	/* Patterned secrets such as all zeros are not special to HKDF, AES or Ed25519, so their edge cases add nothing */
	public static final class Secrets implements ArbitrarySupplier<byte[]> {
		@Override
		public Arbitrary<byte[]> get() {
			return Arbitraries.bytes().array(byte[].class).ofSize(Fingerprint.SECRET_SIZE).withoutEdgeCases();
		}
	}
}
