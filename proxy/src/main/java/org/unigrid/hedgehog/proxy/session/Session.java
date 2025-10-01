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

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryHeader;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* A session between a client and its exit after the handshake. Both ends use this class and differ only in
   the direction they seal in. Each end writes sealed entries to its own feed and opens those of its peer. */
public final class Session {
	private static final Logger LOG = LoggerFactory.getLogger(Session.class);

	private final SessionContext context;
	private final Fingerprint fingerprint;
	private final WriterId peer;
	private final SessionCipher cipher;
	private long lastActivity;
	private boolean open = true;

	Session(SessionContext context, Fingerprint fingerprint, WriterId peer, SessionCipher cipher) {
		this.context = context;
		this.fingerprint = fingerprint;
		this.peer = peer;
		this.cipher = cipher;
		lastActivity = context.now();
	}

	/* The exit's side of the handshake: answers an OPEN that names this gridnode with an ACCEPT. Nothing here
	   remembers an OPEN, so the host offers each one once and keeps one session per fingerprint: a repeat is
	   answered again with a fresh key. */
	public static Optional<Session> accept(SessionContext context, Entry open, String ownId) {
		if (!isOpenNaming(context, open, ownId)) {
			return Optional.empty();
		}

		final EntryHeader header = open.header();
		final EphemeralKey key = EphemeralKey.generate(context.random());

		try {
			final SessionKeys keys = Handshake.exitKeys(key, open, context.writer().writer());
			final SessionCipher cipher = new SessionCipher(keys, Direction.EXIT_TO_CLIENT, header.chainId(),
				header.fingerprint());

			context.post(header.fingerprint(), EntryKind.ACCEPT, Handshake.accept(key, keys));
			return Optional.of(new Session(context, header.fingerprint(), header.writer(), cipher));
		} catch (HandshakeException ex) {
			return Optional.empty();
		}
	}

	/* The cheap refusals, before a key is generated: an exit id that cannot fit an entry names nobody, as the
	   chain would not have taken the entry */
	private static boolean isOpenNaming(SessionContext context, Entry open, String ownId) {
		final EntryHeader header = open.header();

		if (header.kind() != EntryKind.OPEN || !header.chainId().equals(context.writer().chainId())) {
			return false;
		}

		final byte[] payload = open.payload();

		return payload.length <= context.maxPayloadBytes() && Handshake.names(payload, ownId);
	}

	public Fingerprint fingerprint() {
		return fingerprint;
	}

	public WriterId peer() {
		return peer;
	}

	public synchronized boolean isOpen() {
		return open;
	}

	/* The most bytes one Data message can carry within one entry */
	public int maxDataBytes() {
		return context.maxPayloadBytes() - SessionCipher.OVERHEAD - SealedCodec.DATA_OVERHEAD;
	}

	public synchronized void send(SealedMessage message) {
		if (!open) {
			throw new IllegalStateException("Session " + fingerprint + " is closed");
		}

		final byte[] plaintext = SealedCodec.encode(message);

		if (plaintext.length + SessionCipher.OVERHEAD > context.maxPayloadBytes()) {
			throw new IllegalArgumentException("A message of " + plaintext.length
				+ " bytes does not fit an entry");
		}

		/* A Close ends the session even when it cannot be posted, so that a refusing outbox cannot keep it open */
		open = !(message instanceof Close);
		context.post(fingerprint, EntryKind.SEALED, cipher.seal(plaintext));
		lastActivity = context.now();
	}

	/* Takes any entry of the chain and opens the sealed ones of the peer for this session. One that does not
	   open ends the session: the peer is told and the caller gets the same Close. */
	public synchronized Optional<SealedMessage> receive(Entry entry) {
		if (!open || !isFromPeer(entry.header())) {
			return Optional.empty();
		}

		try {
			final byte[] plaintext = cipher.open(entry.payload());
			final SealedMessage message = SealedCodec.decode(plaintext, context.maxPayloadBytes());

			lastActivity = context.now();
			open = !(message instanceof Close);
			return Optional.of(message);
		} catch (SealException | MalformedMessageException ex) {
			return Optional.of(end(CloseReason.BROKEN));
		} catch (RuntimeException ex) {
			/* A failure of the cipher or the codec itself, not of the record, ends the session rather than
			   reaching the network handler. Its class is logged before the Close is posted, which may throw
			   too, so that it does not pass for tampering; the message is left out as it may quote the
			   record. */
			LOG.warn("Closing a session after an unexpected {} while opening a record", ex.getClass().getName());
			return Optional.of(end(CloseReason.BROKEN));
		}
	}

	/* Ends the session from this side and tells the peer why */
	public synchronized Optional<Close> close(CloseReason reason) {
		return open ? Optional.of(end(reason)) : Optional.empty();
	}

	private Close end(CloseReason reason) {
		final Close close = new Close(reason);

		send(close);
		return close;
	}

	private boolean isFromPeer(EntryHeader header) {
		return header.kind() == EntryKind.SEALED && peer.equals(header.writer())
			&& fingerprint.equals(header.fingerprint());
	}
}
