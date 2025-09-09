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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/* One AES-256 key per direction and a confirmation value: the three 32-byte blocks of one HKDF-SHA256
   expansion over the X25519 secret, with the fingerprint as salt and "hedgehog-proxy/session" followed by the
   whole transcript as info. Two ends that saw different transcripts derive unrelated keys and confirmations. */
public final class SessionKeys {
	public static final int KEY_SIZE = 32;
	private static final byte[] LABEL = "hedgehog-proxy/session".getBytes(StandardCharsets.US_ASCII);

	private final byte[] clientToExit;
	private final byte[] exitToClient;
	private final byte[] confirmation;

	private SessionKeys(byte[] keys) {
		clientToExit = Arrays.copyOfRange(keys, 0, KEY_SIZE);
		exitToClient = Arrays.copyOfRange(keys, KEY_SIZE, 2 * KEY_SIZE);
		confirmation = Arrays.copyOfRange(keys, 2 * KEY_SIZE, 3 * KEY_SIZE);
	}

	public static SessionKeys derive(byte[] sharedSecret, Transcript transcript) {
		final HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
		final byte[] bytes = transcript.bytes();
		final byte[] info = ByteBuffer.allocate(LABEL.length + bytes.length).put(LABEL).put(bytes).array();
		final byte[] keys = new byte[3 * KEY_SIZE];

		generator.init(new HKDFParameters(sharedSecret, transcript.fingerprint().bytes(), info));
		generator.generateBytes(keys, 0, keys.length);
		return new SessionKeys(keys);
	}

	public byte[] key(Direction direction) {
		return (direction == Direction.CLIENT_TO_EXIT ? clientToExit : exitToClient).clone();
	}

	/* What the exit's ACCEPT carries to show it derived these keys: a block of the same expansion, which
	   reveals nothing of the keys */
	public byte[] confirmation() {
		return confirmation.clone();
	}

	/* Takes as long for a value that agrees in a long prefix as for one that agrees in none, so a peer that
	   sends guesses learns nothing from the time the refusal takes */
	public boolean confirms(byte[] received) {
		return MessageDigest.isEqual(confirmation, received);
	}
}
