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
import java.util.Arrays;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryHeader;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* OPEN carries the client's X25519 key followed by the UTF-8 id of the gridnode it chose as exit, so only
   that member answers. ACCEPT carries the exit's X25519 key followed by the confirmation of the keys it
   derived. Both ends derive them from the whole transcript, so an ACCEPT that was made for another OPEN, or
   that another writer posted again, confirms nothing to the client. Every key goes through the agreement
   before the derivation, which is what refuses a key of low order or of the wrong size. */
final class Handshake {
	static final int ACCEPT_SIZE = EphemeralKey.SIZE + SessionKeys.KEY_SIZE;

	private Handshake() {
		/* Static helpers only */
	}

	static byte[] open(EphemeralKey key, String exitId) {
		final byte[] id = exitId.getBytes(StandardCharsets.UTF_8);

		return ByteBuffer.allocate(EphemeralKey.SIZE + id.length).put(key.publicKey()).put(id).array();
	}

	static boolean names(byte[] openPayload, String exitId) {
		final byte[] id = exitId.getBytes(StandardCharsets.UTF_8);

		return openPayload.length == EphemeralKey.SIZE + id.length
			&& Arrays.equals(openPayload, EphemeralKey.SIZE, openPayload.length, id, 0, id.length);
	}

	/* A payload shorter than a key stays short, so that the agreement refuses it instead of reading a key
	   the client never sent */
	static byte[] clientKey(byte[] openPayload) {
		return Arrays.copyOf(openPayload, Math.min(openPayload.length, EphemeralKey.SIZE));
	}

	/* The exit's keys: the client's key, writer, fingerprint and chain are those of its OPEN */
	static SessionKeys exitKeys(EphemeralKey own, Entry open, WriterId exit) throws HandshakeException {
		final EntryHeader header = open.header();
		final byte[] clientKey = clientKey(open.payload());

		return SessionKeys.derive(own.agree(clientKey), new Transcript(header.chainId(), header.fingerprint(),
			clientKey, own.publicKey(), header.writer(), exit));
	}

	static byte[] accept(EphemeralKey own, SessionKeys keys) {
		return ByteBuffer.allocate(ACCEPT_SIZE).put(own.publicKey()).put(keys.confirmation()).array();
	}

	/* The client's keys from an ACCEPT, taking the chain, fingerprint and writer from its own side and the
	   exit's writer from the ACCEPT. One that does not confirm the same transcript is refused. */
	static SessionKeys clientKeys(EphemeralKey own, ChainId chainId, Fingerprint fingerprint, WriterId client,
		Entry accept) throws HandshakeException {

		final byte[] payload = accept.payload();

		if (payload.length != ACCEPT_SIZE) {
			throw new HandshakeException("An ACCEPT is " + ACCEPT_SIZE + " bytes, found " + payload.length);
		}

		final byte[] exitKey = Arrays.copyOf(payload, EphemeralKey.SIZE);
		final byte[] confirmation = Arrays.copyOfRange(payload, EphemeralKey.SIZE, ACCEPT_SIZE);
		final SessionKeys keys = SessionKeys.derive(own.agree(exitKey), new Transcript(chainId, fingerprint,
			own.publicKey(), exitKey, client, accept.header().writer()));

		if (!keys.confirms(confirmation)) {
			throw new HandshakeException("The ACCEPT does not confirm this handshake");
		}

		return keys;
	}
}
