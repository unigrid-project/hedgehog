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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryHeader;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* The client's side of a session: posts OPEN naming the exit it chose, completes the handshake on that
   fingerprint's ACCEPT and sends its Connect at once. An OPEN that nobody answers in time is posted again with
   a new fingerprint towards another exit. From then on it relays to and from a Session. */
public final class ClientSession {
	public static final long ACCEPT_TIMEOUT_MILLIS = 5000;
	public static final int MAX_ATTEMPTS = 3;

	private static final Logger LOG = LoggerFactory.getLogger(ClientSession.class);

	private final SessionContext context;
	private final ExitSelector exits;
	private final Connect connect;
	private final List<String> tried = new ArrayList<>();
	private Attempt attempt;
	private Session session;
	private boolean closed;

	private record Attempt(Fingerprint fingerprint, EphemeralKey key, String exit, long startedAt) {
		/* One OPEN, when it was posted and the key that answers it, which is null once the attempt has ended */
	}

	public ClientSession(SessionContext context, ExitSelector exits, Connect connect) {
		this.context = Objects.requireNonNull(context, "context");
		this.exits = Objects.requireNonNull(exits, "exits");
		this.connect = Objects.requireNonNull(connect, "connect");
	}

	/* Posts the first OPEN; false, and closed, when the group offers no exit */
	public synchronized boolean open() {
		if (state() != SessionState.NEW) {
			throw new IllegalStateException("The session was opened before");
		}

		if (attempt()) {
			return true;
		}

		end();
		return false;
	}

	public synchronized SessionState state() {
		if (session != null) {
			return session.isOpen() ? SessionState.OPEN : SessionState.CLOSED;
		}

		if (closed) {
			return SessionState.CLOSED;
		}

		return attempt == null ? SessionState.NEW : SessionState.OPENING;
	}

	public synchronized Optional<Fingerprint> fingerprint() {
		return Optional.ofNullable(attempt).map(Attempt::fingerprint);
	}

	public synchronized Optional<String> exit() {
		return Optional.ofNullable(attempt).map(Attempt::exit);
	}

	/* The feed the exit answered from, known once its ACCEPT confirmed the handshake: what the chain gives up on in
	   that feed is what this session lost */
	public synchronized Optional<WriterId> exitWriter() {
		return Optional.ofNullable(session).map(Session::peer);
	}

	/* Takes any entry of the chain: the ACCEPT that completes the handshake, then the exit's sealed messages */
	public synchronized Optional<SealedMessage> receive(Entry entry) {
		if (session != null) {
			return session.receive(entry);
		}

		if (state() == SessionState.OPENING && isAccept(entry.header())) {
			return establish(entry);
		}

		return Optional.empty();
	}

	public synchronized void send(SealedMessage message) {
		if (session == null) {
			throw new IllegalStateException("The session has no exit yet");
		}

		session.send(message);
	}

	/* Called on the host's schedule. The OPEN of an attempt that no ACCEPT answered in time is replaced, until
	   the attempts or the exits are used up and the session is closed as unreachable. An established session
	   closes when it has been idle for too long. */
	public synchronized Optional<Close> tick() {
		if (session != null) {
			return session.tick();
		}

		return isUnanswered() ? retry() : Optional.empty();
	}

	/* Ends the session from the client's side. Before an exit answered there is nobody to tell, and a late
	   ACCEPT finds the session closed. */
	public synchronized Optional<Close> close(CloseReason reason) {
		if (session != null) {
			return session.close(reason);
		}

		if (closed) {
			return Optional.empty();
		}

		end();
		return Optional.of(new Close(reason));
	}

	private boolean isUnanswered() {
		return state() == SessionState.OPENING && context.now() - attempt.startedAt() >= ACCEPT_TIMEOUT_MILLIS;
	}

	private Optional<Close> retry() {
		if (tried.size() < MAX_ATTEMPTS && attempt()) {
			return Optional.empty();
		}

		end();
		return Optional.of(new Close(CloseReason.UNREACHABLE));
	}

	private boolean attempt() {
		final byte[] bytes = new byte[Fingerprint.SIZE];

		context.random().nextBytes(bytes);

		final Fingerprint fingerprint = new Fingerprint(bytes);
		final Optional<String> exit = choose(fingerprint);

		if (exit.isEmpty()) {
			return false;
		}

		final EphemeralKey key = EphemeralKey.generate(context.random());

		tried.add(exit.get());
		attempt = new Attempt(fingerprint, key, exit.get(), context.now());
		context.post(fingerprint, EntryKind.OPEN, Handshake.open(key, exit.get()));
		return true;
	}

	/* The first exit of the fingerprint's rendezvous order that was not tried and whose id fits the OPEN. The
	   search ends when the selector answers an exit it was told to pass over, as it would be asked for ever. */
	private Optional<String> choose(Fingerprint fingerprint) {
		final Set<String> passedOver = new HashSet<>(tried);
		Optional<String> exit = exits.exit(fingerprint, passedOver);

		while (exit.isPresent() && !fits(exit.get()) && passedOver.add(exit.get())) {
			exit = exits.exit(fingerprint, passedOver);
		}

		return exit.filter(this::fits);
	}

	/* An OPEN that does not fit one entry is refused by every replica of the chain, and the gap it leaves in the
	   feed holds up whatever this writer posts next */
	private boolean fits(String exitId) {
		return EphemeralKey.SIZE + exitId.getBytes(StandardCharsets.UTF_8).length <= context.maxPayloadBytes();
	}

	private boolean isAccept(EntryHeader header) {
		return header.kind() == EntryKind.ACCEPT && header.chainId().equals(context.writer().chainId())
			&& attempt.fingerprint().equals(header.fingerprint());
	}

	private Optional<SealedMessage> establish(Entry accept) {
		final ChainId chainId = context.writer().chainId();

		try {
			final SessionKeys keys = Handshake.clientKeys(attempt.key(), chainId, attempt.fingerprint(),
				context.writer().writer(), accept);
			final SessionCipher cipher = new SessionCipher(keys, Direction.CLIENT_TO_EXIT, chainId,
				attempt.fingerprint());
			final Session established = new Session(context, attempt.fingerprint(), accept.header().writer(),
				cipher);

			established.send(connect);
			session = established;
			forgetKey();
			return Optional.empty();
		} catch (HandshakeException ex) {
			/* Any writer of the chain can post an ACCEPT for this fingerprint. One that does not confirm this
			   handshake is passed over, so the chosen exit's own ACCEPT can still complete it. */
			return Optional.empty();
		} catch (RuntimeException ex) {
			/* A failure of the derivation, the cipher or the post, not of the ACCEPT, ends the attempt rather
			   than reaching the network handler. The class is logged and the message is left out, as it may
			   quote the ACCEPT. */
			LOG.warn("Closing a session after an unexpected {} while completing the handshake",
				ex.getClass().getName());
			end();
			return Optional.of(new Close(CloseReason.BROKEN));
		}
	}

	/* Every way for the session to close goes through here, so that none can leave the key behind */
	private void end() {
		closed = true;
		forgetKey();
	}

	/* The private key has no use once the handshake is over or given up, and no attempt exists before the first
	   OPEN */
	private void forgetKey() {
		if (attempt != null) {
			attempt = new Attempt(attempt.fingerprint(), null, attempt.exit(), attempt.startedAt());
		}
	}
}
