/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

import java.nio.ByteBuffer;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* What the two ends of a handshake must agree on: the chain, the fingerprint, both ephemeral public keys as
   sent and both writers. Every field has a fixed width, so no two transcripts share an encoding. */
public final class Transcript {
	private final Fingerprint fingerprint;
	private final byte[] bytes;

	public Transcript(ChainId chainId, Fingerprint fingerprint, byte[] clientKey, byte[] exitKey, WriterId client,
		WriterId exit) {

		requireKey(clientKey);
		requireKey(exitKey);
		this.fingerprint = fingerprint;
		bytes = ByteBuffer.allocate(ChainId.SIZE + Fingerprint.SIZE + 2 * EphemeralKey.SIZE + 2 * WriterId.SIZE)
			.put(chainId.bytes()).put(fingerprint.bytes()).put(clientKey).put(exitKey).put(client.bytes())
			.put(exit.bytes()).array();
	}

	public Fingerprint fingerprint() {
		return fingerprint;
	}

	public byte[] bytes() {
		return bytes.clone();
	}

	private static void requireKey(byte[] key) {
		if (key.length != EphemeralKey.SIZE) {
			throw new IllegalArgumentException("An X25519 public key is exactly " + EphemeralKey.SIZE
				+ " bytes, found " + key.length);
		}
	}
}
