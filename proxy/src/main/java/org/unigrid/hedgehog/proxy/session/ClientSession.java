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

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Gap;
import org.unigrid.hedgehog.proxy.chain.Tag;
import org.unigrid.hedgehog.proxy.session.NoiseNk.Completed;
import org.unigrid.hedgehog.proxy.session.NoiseNk.Initiation;
import org.unigrid.hedgehog.proxy.session.NoiseNk.Initiator;

/* The client's side of a session: posts an opening sealed to the sealing key of the exit it chose, completes the
   handshake on the answer that carries the answer tag of that opening and sends its Connect at once. Nothing of
   the opening names the exit: it is the exit's key that opens it, padded to the smallest class under a random tag.
   An opening that nobody answers in time is replaced by one towards another exit. From then on it relays to and
   from a Session. */
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

	private record Attempt(Initiator initiator, Tag answerTag, String exit, long startedAt) {
		/* One opening, when it was posted, the tag of its answer and the state that finishes it, which is null once
		   the attempt has ended */
	}

	public ClientSession(SessionContext context, ExitSelector exits, Connect connect) {
		this.context = Objects.requireNonNull(context, "context");
		this.exits = Objects.requireNonNull(exits, "exits");
		this.connect = Objects.requireNonNull(connect, "connect");
	}

	/* Posts the first opening; false, and closed, when the group offers no exit */
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

	public synchronized Optional<String> exit() {
		return Optional.ofNullable(attempt).map(Attempt::exit);
	}

	/* Whether the entry is the next one this session waits for: the answer while it opens, then the exit's next
	   message */
	public synchronized boolean expects(Entry entry) {
		if (session != null) {
			return session.expects(entry);
		}

		return isAnswer(entry);
	}

	/* Takes any entry of the chain: the answer that completes the handshake, then the exit's sealed messages */
	public synchronized Optional<SealedMessage> receive(Entry entry) {
		if (session != null) {
			return session.receive(entry);
		}

		return isAnswer(entry) ? establish(entry) : Optional.empty();
	}

	/* Whether the chain gave up on entries of the exit that belong to this session */
	public synchronized boolean isNamedIn(Gap gap) {
		return session != null && session.isNamedIn(gap);
	}

	public synchronized void send(SealedMessage message) {
		if (session == null) {
			throw new IllegalStateException("The session has no exit yet");
		}

		session.send(message);
	}

	/* Called on the host's schedule. The opening of an attempt that no answer completed in time is replaced, until
	   the attempts or the exits are used up and the session is closed as unreachable. An established session
	   closes when it has been idle for too long. */
	public synchronized Optional<Close> tick() {
		if (session != null) {
			return session.tick();
		}

		return isUnanswered() ? retry() : Optional.empty();
	}

	/* Ends the session from the client's side. Before an exit answered there is nobody to tell, and a late
	   answer finds the session closed. */
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

	/* The first exit the selector offers that was not tried and whose key opens a handshake. An exit whose key is
	   no key of X25519, or one of low order, is passed over without using up an attempt. The search ends when the
	   selector answers an exit it was told to pass over, as it would be asked for ever. */
	private boolean attempt() {
		final Set<String> passedOver = new HashSet<>(tried);

		for (Optional<ExitCandidate> exit = exits.exit(passedOver); exit.isPresent();
			exit = exits.exit(passedOver)) {

			if (attempt(exit.get())) {
				return true;
			}

			if (!passedOver.add(exit.get().id())) {
				return false;
			}
		}

		return false;
	}

	private boolean attempt(ExitCandidate exit) {
		final Initiation initiation;

		try {
			initiation = NoiseNk.initiate(exit.sealingKey(), Session.prologue(context.writer().chainId(),
				context.writer().writer()), context.random());
		} catch (HandshakeException ex) {
			return false;
		}

		final byte[] payload = context.padded(initiation.message());
		final Tag answerTag = Tags.of(initiation.state().answerTagKey(), Direction.EXIT_TO_CLIENT, 0);

		tried.add(exit.id());
		attempt = new Attempt(initiation.state(), answerTag, exit.id(), context.now());
		context.post(Tag.random(context.random()), payload);
		return true;
	}

	/* The answer of the current attempt on the chain of this session, while the attempt still waits for it */
	private boolean isAnswer(Entry entry) {
		return state() == SessionState.OPENING && attempt.initiator() != null
			&& entry.header().chainId().equals(context.writer().chainId())
			&& MessageDigest.isEqual(Session.tagOf(entry).bytes(), attempt.answerTag().bytes());
	}

	private Optional<SealedMessage> establish(Entry answer) {
		final byte[] payload = answer.payload();

		try {
			final Completed completed = attempt.initiator().complete(Arrays.copyOf(payload,
				Math.min(payload.length, Session.ANSWER_SIZE)));

			/* The answer names the writer of the exit under the handshake, so a copy that another writer
			   posted, which would take the exit's feed for that writer's, is passed over */
			if (!MessageDigest.isEqual(completed.payload(), answer.header().writer().bytes())) {
				return Optional.empty();
			}

			final Session established = new Session(context, answer.header().writer(),
				Direction.CLIENT_TO_EXIT, completed.secrets());

			established.send(connect);
			session = established;
			forgetKey();
			return Optional.empty();
		} catch (HandshakeException ex) {
			/* Whoever saw the answer can post its tag again on other bytes. Those do not complete this
			   handshake and are passed over, so that the exit's own answer can still complete it. */
			return Optional.empty();
		} catch (RuntimeException ex) {
			/* A failure of the derivation, the cipher or the post, not of the answer, ends the attempt rather
			   than reaching the network handler. The class is logged and the message is left out, as it may
			   quote the answer. */
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

	/* The ephemeral key in the handshake state has no use once the handshake is over or given up, and no attempt
	   exists before the first opening */
	private void forgetKey() {
		if (attempt != null) {
			attempt = new Attempt(null, attempt.answerTag(), attempt.exit(), attempt.startedAt());
		}
	}
}
