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

package org.unigrid.hedgehog.model.gridnode;

import java.util.Optional;
import org.unigrid.hedgehog.proxy.session.NoiseNk;

/* The X25519 sealing key of this gridnode: clients seal their sessions to the public half it announces, and its
   exit opens them with the private half. A node that is no gridnode has none. Neither half is ever shown. */
public final class SealingKey {
	private static final SealingKey NONE = new SealingKey(null, null, null);

	private final byte[] privateKey;
	private final byte[] publicKey;
	private final NoiseNk.StaticKey staticKey;

	private SealingKey(byte[] privateKey, byte[] publicKey, NoiseNk.StaticKey staticKey) {
		this.privateKey = privateKey;
		this.publicKey = publicKey;
		this.staticKey = staticKey;
	}

	public static SealingKey none() {
		return NONE;
	}

	/* Refuses a private key of the wrong size, as the public half cannot be derived from it */
	public static SealingKey of(byte[] privateKey) {
		final byte[] own = privateKey.clone();

		return new SealingKey(own, SealingKeyFile.publicKey(own), NoiseNk.StaticKey.of(own));
	}

	public Optional<byte[]> privateKey() {
		return Optional.ofNullable(privateKey).map(byte[]::clone);
	}

	public Optional<byte[]> publicKey() {
		return Optional.ofNullable(publicKey).map(byte[]::clone);
	}

	/* The private half made ready once for the openings the exit tries, which hands out none of its bytes, so a
	   trial neither copies the key nor derives its public half again */
	public Optional<NoiseNk.StaticKey> staticKey() {
		return Optional.ofNullable(staticKey);
	}

	@Override
	public String toString() {
		return privateKey == null ? "SealingKey[none]" : "SealingKey[held]";
	}
}
