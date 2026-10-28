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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryHeader;
import org.unigrid.hedgehog.proxy.chain.Gap;
import org.unigrid.hedgehog.proxy.chain.Tag;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.session.NoiseNk.Answered;
import org.unigrid.hedgehog.proxy.session.NoiseNk.Responder;
import org.unigrid.hedgehog.proxy.session.NoiseNk.SessionSecrets;

/* A session between a client and its exit after the handshake. Both ends use this class and differ only in
   the direction they seal in. Each end writes sealed entries to its own feed and opens those of its peer: message
   n of a direction carries the tag of n under the tag key of that direction. Only the peer's feed counts. Every
   writer of the chain sees every entry, so what another writer posts under a tag of the session, copied or forged,
   is passed over and ends nothing. */
public final class Session {
	/* The tags of the peer an end keeps ahead of the next one. A peer entry whose tag is among them but is not the
	   next one, or a gap that names one, means messages of the session were lost. */
	static final int WINDOW = 64;

	/* An answer carries the exit's writer as its payload, under the handshake */
	public static final int ANSWER_SIZE = NoiseNk.MESSAGE_SIZE + WriterId.SIZE;

	private static final Logger LOG = LoggerFactory.getLogger(Session.class);

	private final SessionContext context;
	private final WriterId peer;
	private final Direction sending;
	private final SealingCipher.Sealer sealer;
	private final SealingCipher.Opener opener;
	private final byte[] ownTags;
	private final byte[] peerTags;
	private final Map<Tag, Long> ahead = new HashMap<>();
	private long sent;
	private long received;
	private long lastActivity;
	private boolean open = true;

	Session(SessionContext context, WriterId peer, Direction sending, SessionSecrets secrets) {
		final boolean client = sending == Direction.CLIENT_TO_EXIT;

		this.context = context;
		this.peer = peer;
		this.sending = sending;
		sealer = SealingCipher.sealer(client ? secrets.clientToExit() : secrets.exitToClient(), sending,
			secrets.binding());
		opener = SealingCipher.opener(client ? secrets.exitToClient() : secrets.clientToExit(), sending.reverse(),
			secrets.binding());
		ownTags = client ? secrets.clientTags() : secrets.exitTags();
		peerTags = client ? secrets.exitTags() : secrets.clientTags();

		for (long counter = 0; counter < WINDOW; counter++) {
			ahead.put(Tags.of(peerTags, sending.reverse(), counter), counter);
		}

		lastActivity = context.now();
	}

	/* The exit's side of the handshake: an entry of the smallest class on the chain of the context that opens
	   under this gridnode's sealing key is an opening for it, and is answered at once. The prologue binds the
	   chain and the writer of the opening, so a copy of it under another writer opens nowhere. The answer names the
	   exit's own writer, so a copy of the answer under another writer completes nothing at the client. Whatever
	   does not open is not for this exit, and nothing of it is kept. */
	public static Optional<Session> tryAccept(SessionContext context, Entry entry, byte[] sealingPrivate) {
		final EntryHeader header = entry.header();

		if (!header.chainId().equals(context.writer().chainId())
			|| entry.size() - Entry.OVERHEAD != context.smallestPayloadBytes()) {
			return Optional.empty();
		}

		final Optional<Responder> responder = NoiseNk.respond(sealingPrivate, prologue(header.chainId(),
			header.writer()), Arrays.copyOf(entry.payload(), NoiseNk.MESSAGE_SIZE));

		if (responder.isEmpty()) {
			return Optional.empty();
		}

		final Answered answered = responder.get().answer(context.random(), context.writer().writer().bytes());

		context.post(Tags.of(responder.get().answerTagKey(), Direction.EXIT_TO_CLIENT, 0),
			context.padded(answered.message()));
		return Optional.of(new Session(context, header.writer(), Direction.EXIT_TO_CLIENT, answered.secrets()));
	}

	/* What both ends put before the handshake: the chain, and the client's writer on it */
	static byte[] prologue(ChainId chainId, WriterId client) {
		return ByteBuffer.allocate(ChainId.SIZE + WriterId.SIZE).put(chainId.bytes()).put(client.bytes()).array();
	}

	/* The tag an entry carries in place of its fingerprint */
	static Tag tagOf(Entry entry) {
		return new Tag(entry.header().fingerprint().bytes());
	}

	/* The feed of the peer: the client's writer of the opening at the exit, and the writer whose answer completed the
	   handshake at the client */
	public WriterId peer() {
		return peer;
	}

	public synchronized boolean isOpen() {
		return open;
	}

	/* The most bytes one Data message can carry within one entry of the largest class */
	public int maxDataBytes() {
		return context.maxPayloadBytes() - SealingCipher.OVERHEAD - SealedCodec.DATA_OVERHEAD;
	}

	/* Whether the entry is the peer's and carries its next tag, which is the only one that opens */
	public synchronized boolean expects(Entry entry) {
		return isOfPeer(entry) && Long.valueOf(received).equals(ahead.get(tagOf(entry)));
	}

	/* Whether the entry is the peer's and carries a tag that this session still looks out for, the next one or one
	   past it: those after the next one mean that messages before them were lost */
	public synchronized boolean recognises(Entry entry) {
		return isOfPeer(entry) && ahead.containsKey(tagOf(entry));
	}

	/* Whether the chain gave up on entries of the peer that carried a tag this session looks out for */
	public synchronized boolean isNamedIn(Gap gap) {
		return open && peer.equals(gap.writer()) && gap.fingerprints().stream()
			.anyMatch(fingerprint -> ahead.containsKey(new Tag(fingerprint.bytes())));
	}

	private boolean isOfPeer(Entry entry) {
		return open && peer.equals(entry.header().writer());
	}

	/* Each message is sealed to the smallest class that holds it. The counter of the tag moves with the counter of
	   the cipher before the post, so that a post that throws uses up both and the next message is never sealed
	   under a nonce or a tag that went out already. */
	public synchronized void send(SealedMessage message) {
		if (!open) {
			throw new IllegalStateException("The session is closed");
		}

		final byte[] plaintext = SealedCodec.encode(message);

		if (plaintext.length + SealingCipher.OVERHEAD > context.maxPayloadBytes()) {
			throw new IllegalArgumentException("A message of " + plaintext.length
				+ " bytes does not fit an entry");
		}

		/* A Close ends the session even when it cannot be posted, so that a refusing outbox cannot keep it open */
		open = !(message instanceof Close);

		final Tag tag = Tags.of(ownTags, sending, sent);
		final byte[] sealed = sealer.seal(plaintext, context.paddedPayloadBytes(plaintext.length
			+ SealingCipher.OVERHEAD));

		sent++;
		context.post(tag, sealed);
		lastActivity = context.now();
	}

	/* Takes any entry of the chain and opens the next message of the peer. An entry of the peer's feed that carries
	   a later tag, or the next one but does not open, ends the session: the peer is told and the caller gets the same
	   Close. */
	public synchronized Optional<SealedMessage> receive(Entry entry) {
		if (!open || !recognises(entry)) {
			return Optional.empty();
		}

		if (!expects(entry)) {
			return Optional.of(end(CloseReason.BROKEN));
		}

		try {
			final byte[] plaintext = opener.open(entry.payload());

			advance();

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

	/* Ends the session from this side and tells the peer why. The host also calls it with BROKEN for a Gap that
	   names the session. */
	public synchronized Optional<Close> close(CloseReason reason) {
		return open ? Optional.of(end(reason)) : Optional.empty();
	}

	/* Runs on the host's schedule: a session without traffic either way for sessionIdleSeconds closes */
	public synchronized Optional<Close> tick() {
		if (open && context.now() - lastActivity >= context.settings().sessionIdleMillis()) {
			return Optional.of(end(CloseReason.IDLE));
		}

		return Optional.empty();
	}

	private Close end(CloseReason reason) {
		final Close close = new Close(reason);

		send(close);
		return close;
	}

	/* The next tag of the peer moves on by one, and one more joins the far end */
	private void advance() {
		ahead.values().remove(received);
		received++;
		ahead.put(Tags.of(peerTags, sending.reverse(), received + WINDOW - 1), received + WINDOW - 1);
	}
}
