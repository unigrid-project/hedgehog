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

package org.unigrid.hedgehog.proxy.chain;

import java.security.SecureRandom;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

public final class WriterKey {
	public static final int SEED_SIZE = Ed25519PrivateKeyParameters.KEY_SIZE;

	private final Ed25519PrivateKeyParameters privateKey;
	private final WriterId id;

	public WriterKey(byte[] seed) {
		if (seed.length != SEED_SIZE) {
			throw new IllegalArgumentException("A writer key seed is exactly " + SEED_SIZE + " bytes, found "
				+ seed.length);
		}

		privateKey = new Ed25519PrivateKeyParameters(seed, 0);
		id = new WriterId(privateKey.generatePublicKey().getEncoded());
	}

	public static WriterKey generate(SecureRandom random) {
		final byte[] seed = new byte[SEED_SIZE];

		random.nextBytes(seed);
		return new WriterKey(seed);
	}

	public WriterId id() {
		return id;
	}

	public Entry sign(EntryHeader header, byte[] payload) {
		if (!id.equals(header.writer())) {
			throw new IllegalArgumentException("The header names writer " + header.writer() + ", not " + id);
		}

		final byte[] body = EntryCodec.body(header, payload);
		final long hash = FnvJ64.hash(body);

		return new Entry(header, payload, hash, signature(EntryCodec.signed(body, hash)));
	}

	byte[] signature(byte[] message) {
		final Ed25519Signer signer = new Ed25519Signer();

		signer.init(true, privateKey);
		signer.update(message, 0, message.length);
		return signer.generateSignature();
	}
}
