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
   An opening that nobody answers in time is followed by one towards another exit, and whichever exit answers
   first carries the session. From then on it relays to and from a Session. */
public final class ClientSession {
	public static final long ACCEPT_TIMEOUT_MILLIS = 5000;
	public static final int MAX_ATTEMPTS = 3;

	private static final Logger LOG = LoggerFactory.getLogger(ClientSession.class);

	private final SessionContext context;
	private final ExitSelector exits;
	private final Connect connect;
	private final List<String> tried = new ArrayList<>();

	/* The attempts whose answer may still come, at most one for each exit tried. A stall on the way can bring an
	   answer past the timeout, so an attempt that timed out stays until its answer came or the session ended. */
	private final List<Attempt> waiting = new ArrayList<>();
	private String exit;
	private long startedAt;
	private Session session;
	private boolean closed;

	private record Attempt(Initiator initiator, Tag answerTag, String exit) {
		/* One opening, the tag of its answer and the state that finishes it */
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

		return exit == null ? SessionState.NEW : SessionState.OPENING;
	}

	/* The exit of the latest opening, and once one answered, the exit that did */
	public synchronized Optional<String> exit() {
		return Optional.ofNullable(exit);
	}

	/* Whether the entry is the next one this session waits for: an answer to one of its openings, and once the
	   session is open the exit's next message too */
	public synchronized boolean expects(Entry entry) {
		return answered(entry).isPresent() || session != null && session.expects(entry);
	}

	/* Takes any entry of the chain: the answer that completes the handshake, then the exit's sealed messages. An
	   answer that comes once another completed the session is completed too, and its exit told to close. */
	public synchronized Optional<SealedMessage> receive(Entry entry) {
		final Optional<Attempt> answered = answered(entry);

		if (answered.isPresent()) {
			return session == null ? establish(answered.get(), entry) : release(answered.get(), entry);
		}

		return session == null ? Optional.empty() : settled(session.receive(entry));
	}

	/* Whether the chain gave up on entries of the exit that belong to this session */
	public synchronized boolean isNamedIn(Gap gap) {
		return session != null && session.isNamedIn(gap);
	}

	public synchronized void send(SealedMessage message) {
		if (session == null) {
			throw new IllegalStateException("The session has no exit yet");
		}

		try {
			session.send(message);
		} finally {
			settled(Optional.empty());
		}
	}

	/* Called on the host's schedule. When the latest opening went unanswered for the timeout another follows, until
	   the attempts or the exits are used up and the session is closed as unreachable. An established session
	   closes when it has been idle for too long. */
	public synchronized Optional<Close> tick() {
		if (session != null) {
			return settled(session.tick());
		}

		return isUnanswered() ? retry() : Optional.empty();
	}

	/* Ends the session from the client's side. Before an exit answered there is nobody to tell, and a late
	   answer finds the session closed. */
	public synchronized Optional<Close> close(CloseReason reason) {
		if (session != null) {
			return settled(session.close(reason));
		}

		if (closed) {
			return Optional.empty();
		}

		end();
		return Optional.of(new Close(reason));
	}

	private boolean isUnanswered() {
		return state() == SessionState.OPENING && context.now() - startedAt >= ACCEPT_TIMEOUT_MILLIS;
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

		for (Optional<ExitCandidate> candidate = exits.exit(passedOver); candidate.isPresent();
			candidate = exits.exit(passedOver)) {

			if (attempt(candidate.get())) {
				return true;
			}

			if (!passedOver.add(candidate.get().id())) {
				return false;
			}
		}

		return false;
	}

	private boolean attempt(ExitCandidate candidate) {
		final Initiation initiation;

		try {
			initiation = NoiseNk.initiate(candidate.sealingKey(), Session.prologue(context.writer().chainId(),
				context.writer().writer()), context.random());
		} catch (HandshakeException ex) {
			return false;
		}

		final byte[] payload = context.padded(initiation.message());
		final Tag answerTag = Tags.of(initiation.state().answerTagKey(), Direction.EXIT_TO_CLIENT, 0);

		tried.add(candidate.id());
		waiting.add(new Attempt(initiation.state(), answerTag, candidate.id()));
		exit = candidate.id();
		startedAt = context.now();
		context.post(Tag.random(context.random()), payload);
		return true;
	}

	/* The attempt an entry of the chain of this session answers, while the session is not closed */
	private Optional<Attempt> answered(Entry entry) {
		if (state() == SessionState.CLOSED || !entry.header().chainId().equals(context.writer().chainId())) {
			return Optional.empty();
		}

		return waiting.stream().filter(attempt -> MessageDigest.isEqual(entry.header().tag().bytes(),
			attempt.answerTag().bytes())).findFirst();
	}

	private Optional<SealedMessage> establish(Attempt answered, Entry answer) {
		try {
			final Optional<Session> established = complete(answered, answer);

			if (established.isPresent()) {
				established.get().send(connect);
				session = established.get();
				exit = answered.exit();
				waiting.remove(answered);
			}

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

	/* An exit that answered after another one completed the session holds a relay that nobody will use, so it is
	   told to close as an ended session is, and lets the relay go at once. Nothing of it reaches the session. */
	private Optional<SealedMessage> release(Attempt late, Entry answer) {
		try {
			complete(late, answer).ifPresent(other -> {
				waiting.remove(late);
				other.close(CloseReason.DONE);
			});
		} catch (RuntimeException ex) {
			/* The exit lets the relay go on its own once no Connect came, so only the class is logged */
			LOG.warn("Leaving the relay of a late answer to its exit after an unexpected {}",
				ex.getClass().getName());
			waiting.remove(late);
		}

		return Optional.empty();
	}

	/* The session that an answer completes under its attempt, and none for bytes that do not */
	private Optional<Session> complete(Attempt answered, Entry answer) {
		final byte[] payload = answer.payload();

		try {
			final Completed completed = answered.initiator().complete(Arrays.copyOf(payload,
				Math.min(payload.length, Session.ANSWER_SIZE)));

			/* The answer names the writer of the exit under the handshake, so a copy that another writer
			   posted, which would take the exit's feed for that writer's, is passed over */
			if (!MessageDigest.isEqual(completed.payload(), answer.header().writer().bytes())) {
				return Optional.empty();
			}

			return Optional.of(new Session(context, answer.header().writer(), Direction.CLIENT_TO_EXIT,
				completed.secrets()));
		} catch (HandshakeException ex) {
			/* Whoever saw the answer can post its tag again on other bytes. Those do not complete this
			   handshake and are passed over, so that the exit's own answer can still complete it. */
			return Optional.empty();
		}
	}

	/* What the session produced, once the attempts still waiting are let go of if it closed with it */
	private <T> Optional<T> settled(Optional<T> produced) {
		if (!session.isOpen()) {
			waiting.clear();
		}

		return produced;
	}

	/* Every way for the session to close before an exit answered goes through here, so that none can leave a key
	   behind */
	private void end() {
		closed = true;
		waiting.clear();
	}
}
