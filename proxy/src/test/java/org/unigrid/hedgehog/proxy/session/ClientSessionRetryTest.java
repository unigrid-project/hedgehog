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

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Tag;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class ClientSessionRetryTest {
	private static final Connect CONNECT = new Connect(Socks5CommandType.CONNECT, new SocksAddress(
		Socks5AddressType.DOMAIN, "example.org", 443));
	/* A member whose announced key is a point of low order, which opens no handshake */
	private static final String NO_KEY = "gn-no-key";
	private static final int DRAWS_PER_ATTEMPT = 3;

	/* Draws that depend only on how many draws came before and how many bytes are asked for, so that the test
	   can rebuild what attempt number n drew: its private key first, then the padding, then the tag */
	private static final class CountedRandom extends SecureRandom {
		private int draws;

		CountedRandom(int drawsBefore) {
			draws = drawsBefore;
		}

		@Override
		public void nextBytes(byte[] bytes) {
			for (int i = 0; i < bytes.length; i++) {
				bytes[i] = (byte) (draws * 53 + bytes.length * 3 + i);
			}

			draws++;
		}
	}

	/* Always the first member not tried yet, so the order of the exits is known */
	private static ExitSelector inOrder(String... members) {
		return excluded -> Stream.of(members).filter(member -> !excluded.contains(member)).findFirst()
			.map(ClientSessionRetryTest::candidate);
	}

	private static ExitCandidate candidate(String member) {
		return member.startsWith(NO_KEY) ? new ExitCandidate(member, new byte[NoiseNk.KEY_SIZE])
			: SessionFixtures.exit(member);
	}

	/* Whether the holder of the member's sealing key opens the entry */
	private static boolean opensFor(Entry entry, String member) {
		return NoiseNk.respond(SessionFixtures.sealingPrivate(member), Session.prologue(entry.header().chainId(),
			entry.header().writer()), Arrays.copyOf(entry.payload(), NoiseNk.MESSAGE_SIZE)).isPresent();
	}

	private static void timeOut(SessionWire wire) {
		wire.clock.addAndGet(ClientSession.ACCEPT_TIMEOUT_MILLIS);
	}

	private static Tag tagOfAttempt(int attempt) {
		final byte[] bytes = new byte[Tag.SIZE];

		new CountedRandom(DRAWS_PER_ATTEMPT * attempt + 2).nextBytes(bytes);
		return new Tag(bytes);
	}

	private static byte[] privateKeyOfAttempt(int attempt) {
		return new X25519PrivateKeyParameters(new CountedRandom(DRAWS_PER_ATTEMPT * attempt)).getEncoded();
	}

	private static ClientSession clientOf(SessionWire wire, SecureRandom random, ExitSelector selector) {
		wire.client = ClientSessionTest.clientOf(wire, wire.posted::add, random, selector);
		return wire.client;
	}

	/* The answer of the member's key to an opening, taken off the wire before anyone else sees it */
	private static Entry answerTo(SessionWire wire, Entry opening, String exitId) {
		Session.tryAccept(wire.exitContext, opening, SessionFixtures.sealingPrivate(exitId)).orElseThrow();
		return wire.posted.pollLast();
	}

	private static boolean holdsNoKeyOfAnyAttempt(ClientSession client, int attempts) throws IllegalAccessException {
		for (int attempt = 0; attempt < attempts; attempt++) {
			if (ClientSessionTest.holds(client, privateKeyOfAttempt(attempt))) {
				return false;
			}
		}

		return true;
	}

	@Example
	public void shouldRetryWithANewOpeningTowardsTheNextExit() {
		final SessionWire wire = new SessionWire("gn-c");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b", "gn-c"), CONNECT);
		final List<Tag> tags = new ArrayList<>();

		client.open();

		for (String silent : List.of("gn-a", "gn-b")) {
			assertThat(opensFor(wire.posted.peek(), silent), is(true));
			tags.add(wire.posted.peek().header().tag());
			wire.pump();
			assertThat(client.exit(), equalTo(Optional.of(silent)));
			assertThat(client.state(), is(SessionState.OPENING));
			timeOut(wire);
			assertThat(client.tick(), equalTo(Optional.empty()));
		}

		tags.add(wire.posted.peek().header().tag());
		wire.pump();
		assertThat(client.exit(), equalTo(Optional.of("gn-c")));
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(new HashSet<>(tags).size(), is(3));
		assertThat(wire.atExit, equalTo(List.of(CONNECT)));
	}

	@Example
	public void shouldWaitTheWholeTimeoutBeforeRetrying() {
		final SessionWire wire = new SessionWire("gn-b");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b"), CONNECT);

		client.open();
		wire.pump();
		wire.clock.addAndGet(ClientSession.ACCEPT_TIMEOUT_MILLIS - 1);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.exit(), equalTo(Optional.of("gn-a")));
	}

	@Example
	public void shouldGiveUpAfterTheLastAttempt() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b", "gn-c", "gn-d", "gn-z"), CONNECT);
		int opens = 0;

		client.open();

		for (int attempt = 1; attempt < ClientSession.MAX_ATTEMPTS; attempt++) {
			opens += wire.posted.size();
			wire.pump();
			timeOut(wire);
			assertThat(client.tick(), equalTo(Optional.empty()));
		}

		opens += wire.posted.size();
		wire.pump();
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(client.state(), is(SessionState.CLOSED));
		assertThat(opens, is(ClientSession.MAX_ATTEMPTS));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.tick(), equalTo(Optional.empty()));
	}

	@Example
	public void shouldGiveUpWhenNoOtherExitIsLeft() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a"), CONNECT);

		client.open();
		wire.pump();
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(client.state(), is(SessionState.CLOSED));
	}

	@Example
	public void shouldIgnoreTheLateAnswerOfAnEarlierAttempt() {
		final SessionWire wire = new SessionWire("gn-a");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b"), CONNECT);

		client.open();
		timeOut(wire);
		client.tick();
		wire.pump();
		assertThat(wire.exits.size(), is(1));
		assertThat(wire.atExit, equalTo(List.of()));
		assertThat(client.state(), is(SessionState.OPENING));
		assertThat(client.exit(), equalTo(Optional.of("gn-b")));
	}

	@Example
	public void shouldPassOverAnExitWhoseKeyIsNoKey() {
		final SessionWire wire = new SessionWire("gn-b");
		final ClientSession client = wire.client(inOrder(NO_KEY, "gn-b"), CONNECT);

		assertThat(client.open(), is(true));
		wire.pump();
		assertThat(client.exit(), equalTo(Optional.of("gn-b")));
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldNotOpenWhenNoExitHasAKey() {
		final SessionWire wire = new SessionWire("gn-a");
		final ClientSession client = wire.client(inOrder(NO_KEY), CONNECT);

		assertThat(client.open(), is(false));
		assertThat(client.state(), is(SessionState.CLOSED));
		assertThat(wire.posted.isEmpty(), is(true));
	}

	@Example
	public void shouldRetryAtTheTimeoutAndNotOneMillisecondBefore() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b", "gn-c"), CONNECT);

		client.open();
		wire.clock.addAndGet(ClientSession.ACCEPT_TIMEOUT_MILLIS - 1);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(1));
		assertThat(client.exit(), equalTo(Optional.of("gn-a")));
		wire.clock.addAndGet(1);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(2));
		assertThat(client.exit(), equalTo(Optional.of("gn-b")));
	}

	@Example
	public void shouldMeasureTheTimeoutFromTheLatestOpen() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b", "gn-c"), CONNECT);

		client.open();
		timeOut(wire);
		client.tick();
		wire.clock.addAndGet(ClientSession.ACCEPT_TIMEOUT_MILLIS - 1);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(2));
		assertThat(client.exit(), equalTo(Optional.of("gn-b")));
		wire.clock.addAndGet(1);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(3));
		assertThat(client.exit(), equalTo(Optional.of("gn-c")));
	}

	@Example
	public void shouldRetryOnlyOnceHoweverOftenItIsTickedAtTheSameTime() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b", "gn-c"), CONNECT);

		client.open();
		timeOut(wire);

		for (int tick = 0; tick < 3; tick++) {
			assertThat(client.tick(), equalTo(Optional.empty()));
			assertThat(wire.posted.size(), is(2));
			assertThat(client.exit(), equalTo(Optional.of("gn-b")));
		}
	}

	@Example
	public void shouldNotRetryWhenTheClockMovesBack() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b"), CONNECT);

		client.open();
		wire.clock.addAndGet(-ClientSession.ACCEPT_TIMEOUT_MILLIS);
		assertThat(client.tick(), equalTo(Optional.empty()));
		wire.clock.addAndGet(-1);
		assertThat(client.tick(), equalTo(Optional.empty()));
		wire.clock.addAndGet(-3_600_000);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(1));
		assertThat(client.exit(), equalTo(Optional.of("gn-a")));
		assertThat(client.state(), is(SessionState.OPENING));
	}

	@Example
	public void shouldWaitOutTheTimeoutOnTheClockOfTheOpenAfterItWentBack() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b"), CONNECT);

		client.open();
		wire.clock.addAndGet(-3_600_000);
		client.tick();
		wire.clock.addAndGet(3_600_000 + ClientSession.ACCEPT_TIMEOUT_MILLIS - 1);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(1));
		wire.clock.addAndGet(1);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(2));
		assertThat(client.exit(), equalTo(Optional.of("gn-b")));
	}

	@Example
	public void shouldDoNothingWhenTickedBeforeTheOpen() {
		final SessionWire wire = new SessionWire("gn-a");
		final ClientSession client = wire.client(inOrder("gn-a"), CONNECT);

		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.NEW));
		assertThat(client.exit(), equalTo(Optional.empty()));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.open(), is(true));
	}

	@Example
	public void shouldNotRetryOnceTheHandshakeIsDone() {
		final SessionWire wire = new SessionWire("gn-a");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b"), CONNECT);

		client.open();
		wire.pump();
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(client.exit(), equalTo(Optional.of("gn-a")));
	}

	@Example
	public void shouldNotRetryOnceTheSessionIsClosed() {
		final SessionWire wire = new SessionWire("gn-a");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b"), CONNECT);

		client.open();
		wire.pump();
		client.send(new Close(CloseReason.DONE));
		wire.pump();
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.state(), is(SessionState.CLOSED));
	}

	@Example
	public void shouldNotRetryAfterTheHandshakeBrokeDown() {
		final SessionWire wire = new SessionWire("gn-a");
		final AtomicInteger posts = new AtomicInteger();
		final ClientSession client = ClientSessionTest.clientOf(wire, entry -> {
			if (posts.getAndIncrement() > 0) {
				throw new IllegalStateException("The outbox refuses");
			}

			wire.posted.add(entry);
		}, new SecureRandom(), inOrder("gn-a", "gn-b"));

		client.open();
		assertThat(client.receive(answerTo(wire, wire.posted.poll(), "gn-a")),
			equalTo(Optional.of(new Close(CloseReason.BROKEN))));
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(posts.get(), is(2));
		assertThat(client.state(), is(SessionState.CLOSED));
	}

	@Example
	public void shouldDrawTheNewKeyAndTagFromTheRandomnessOfItsContext() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = clientOf(wire, new CountedRandom(0), inOrder("gn-a", "gn-b", "gn-c"));

		client.open();
		timeOut(wire);
		client.tick();
		timeOut(wire);
		client.tick();

		for (int attempt = 0; attempt < ClientSession.MAX_ATTEMPTS; attempt++) {
			final Entry opening = wire.posted.poll();

			assertThat(opening.header().tag(), equalTo(tagOfAttempt(attempt)));
			assertThat(Arrays.copyOf(opening.payload(), NoiseNk.KEY_SIZE), equalTo(new X25519PrivateKeyParameters(
				privateKeyOfAttempt(attempt)).generatePublicKey().getEncoded()));
		}
	}

	@Example
	public void shouldChooseEachExitWithEveryExitTriedSoFarExcluded() {
		final SessionWire wire = new SessionWire("gn-z");
		final List<Set<String>> excluded = new ArrayList<>();
		final ExitSelector ordered = inOrder("gn-a", "gn-b", "gn-c");
		final ClientSession client = wire.client(passedOver -> {
			excluded.add(Set.copyOf(passedOver));
			return ordered.exit(passedOver);
		}, CONNECT);

		client.open();
		timeOut(wire);
		client.tick();
		timeOut(wire);
		client.tick();
		assertThat(wire.posted.size(), is(3));
		assertThat(excluded, equalTo(List.of(Set.of(), Set.of("gn-a"), Set.of("gn-a", "gn-b"))));
	}

	@Example
	public void shouldNeverPostMoreOpensThanTheMaximumWhateverTheSelectorAnswers() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(excluded -> Optional.of(SessionFixtures.exit("gn-a")), CONNECT);
		final List<Optional<Close>> ticks = new ArrayList<>();

		client.open();

		for (int tick = 0; tick < 2 * ClientSession.MAX_ATTEMPTS; tick++) {
			timeOut(wire);
			ticks.add(client.tick());
		}

		assertThat(wire.posted.size(), is(ClientSession.MAX_ATTEMPTS));
		assertThat(ticks.get(ClientSession.MAX_ATTEMPTS - 2), equalTo(Optional.empty()));
		assertThat(ticks.get(ClientSession.MAX_ATTEMPTS - 1),
			equalTo(Optional.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(ticks.stream().filter(Optional::isPresent).count(), is(1L));
	}

	@Example
	public void shouldPassOverAnExitWhoseKeyIsNoKeyWhenRetrying() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a", NO_KEY, "gn-c"), CONNECT);

		client.open();
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(client.exit(), equalTo(Optional.of("gn-c")));
		assertThat(wire.posted.size(), is(2));
		assertThat(opensFor(wire.posted.peekLast(), "gn-c"), is(true));
	}

	@Example
	public void shouldGiveUpWhenNoExitLeftHasAKey() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a", NO_KEY), CONNECT);

		client.open();
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(client.state(), is(SessionState.CLOSED));
		assertThat(wire.posted.size(), is(1));
		assertThat(client.tick(), equalTo(Optional.empty()));
	}

	@Example
	public void shouldAskAgainUntilAnExitHasAKeyWithEachExitWithoutOneExcluded() {
		final String second = NO_KEY + "-2";
		final String third = NO_KEY + "-3";
		final SessionWire wire = new SessionWire("gn-b");
		final List<Set<String>> excluded = new ArrayList<>();
		final ExitSelector ordered = inOrder(NO_KEY, second, third, "gn-b");
		final ClientSession client = wire.client(passedOver -> {
			excluded.add(Set.copyOf(passedOver));
			return ordered.exit(passedOver);
		}, CONNECT);

		assertThat(client.open(), is(true));
		assertThat(excluded, equalTo(List.of(Set.of(), Set.of(NO_KEY), Set.of(NO_KEY, second),
			Set.of(NO_KEY, second, third))));
		assertThat(client.exit(), equalTo(Optional.of("gn-b")));
		wire.pump();
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldIgnoreTheAnswerToAnEarlierAttemptAndTakeTheAnswerToTheLatest() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b"), CONNECT);

		client.open();
		timeOut(wire);
		client.tick();

		final Entry first = wire.posted.poll();
		final Entry second = wire.posted.poll();
		final Entry late = answerTo(wire, first, "gn-a");

		assertThat(client.receive(late), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPENING));
		assertThat(client.exit(), equalTo(Optional.of("gn-b")));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.receive(answerTo(wire, second, "gn-b")), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(wire.posted.size(), is(1));
	}

	@Example
	public void shouldForgetThePrivateKeyOfAnAttemptThatTimedOutAndKeepOnlyTheNewOne() throws IllegalAccessException {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = clientOf(wire, new CountedRandom(0), inOrder("gn-a", "gn-b", "gn-c"));

		client.open();
		assertThat(ClientSessionTest.holds(client, privateKeyOfAttempt(0)), is(true));
		timeOut(wire);
		client.tick();
		assertThat(ClientSessionTest.holds(client, privateKeyOfAttempt(0)), is(false));
		assertThat(ClientSessionTest.holds(client, privateKeyOfAttempt(1)), is(true));
		timeOut(wire);
		client.tick();
		assertThat(ClientSessionTest.holds(client, privateKeyOfAttempt(1)), is(false));
		assertThat(ClientSessionTest.holds(client, privateKeyOfAttempt(2)), is(true));
	}

	@Example
	public void shouldForgetThePrivateKeyWhenTheLastAttemptIsGivenUp() throws IllegalAccessException {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = clientOf(wire, new CountedRandom(0), inOrder("gn-a", "gn-b", "gn-c", "gn-d"));

		client.open();

		for (int attempt = 1; attempt < ClientSession.MAX_ATTEMPTS; attempt++) {
			timeOut(wire);
			client.tick();
		}

		assertThat(ClientSessionTest.holds(client, privateKeyOfAttempt(ClientSession.MAX_ATTEMPTS - 1)), is(true));
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(holdsNoKeyOfAnyAttempt(client, ClientSession.MAX_ATTEMPTS), is(true));
	}

	@Example
	public void shouldForgetThePrivateKeyWhenNoOtherExitIsLeft() throws IllegalAccessException {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = clientOf(wire, new CountedRandom(0), inOrder("gn-a"));

		client.open();
		assertThat(ClientSessionTest.holds(client, privateKeyOfAttempt(0)), is(true));
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(holdsNoKeyOfAnyAttempt(client, 2), is(true));
	}

	@Example
	public void shouldForgetThePrivateKeyWhenNoExitLeftHasAKey() throws IllegalAccessException {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = clientOf(wire, new CountedRandom(0), inOrder("gn-a", NO_KEY));

		client.open();
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(holdsNoKeyOfAnyAttempt(client, 2), is(true));
	}

	@Example
	public void shouldForgetThePrivateKeyWhenClosedBeforeAnExitAnswered() throws IllegalAccessException {
		for (int retries = 0; retries < ClientSession.MAX_ATTEMPTS; retries++) {
			final SessionWire wire = new SessionWire("gn-z");
			final ClientSession client = clientOf(wire, new CountedRandom(0), inOrder("gn-a", "gn-b", "gn-c"));

			client.open();

			for (int retry = 0; retry < retries; retry++) {
				timeOut(wire);
				client.tick();
			}

			assertThat(ClientSessionTest.holds(client, privateKeyOfAttempt(retries)), is(true));
			assertThat(client.close(CloseReason.DONE), equalTo(Optional.of(new Close(CloseReason.DONE))));
			assertThat(holdsNoKeyOfAnyAttempt(client, ClientSession.MAX_ATTEMPTS), is(true));
		}
	}

	@Example
	public void shouldStopAskingWhenTheSelectorKeepsAnsweringAnExitWithoutAKey() {
		final SessionWire wire = new SessionWire("gn-a");
		final AtomicInteger asked = new AtomicInteger();
		final ClientSession client = wire.client(excluded -> {
			if (asked.incrementAndGet() > 10) {
				throw new AssertionError("asked for ever");
			}

			return Optional.of(candidate(NO_KEY));
		}, CONNECT);

		assertThat(client.open(), is(false));
		assertThat(asked.get(), is(2));
		assertThat(client.state(), is(SessionState.CLOSED));
		assertThat(wire.posted.isEmpty(), is(true));
	}

	@Example
	public void shouldHoldNoPrivateKeyOnceARetriedHandshakeIsDone() throws IllegalAccessException {
		final SessionWire wire = new SessionWire("gn-b");
		final ClientSession client = clientOf(wire, new CountedRandom(0), inOrder("gn-a", "gn-b"));

		client.open();
		timeOut(wire);
		client.tick();
		assertThat(ClientSessionTest.holds(client, privateKeyOfAttempt(1)), is(true));
		wire.pump();
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(holdsNoKeyOfAnyAttempt(client, 2), is(true));
	}
}
