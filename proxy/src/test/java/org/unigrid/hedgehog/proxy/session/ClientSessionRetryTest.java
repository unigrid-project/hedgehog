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

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Example;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class ClientSessionRetryTest {
	private static final Connect CONNECT = new Connect(Socks5CommandType.CONNECT, new SocksAddress(
		Socks5AddressType.DOMAIN, "example.org", 443));
	private static final int LONGEST_FITTING_ID = ProxySettings.DEFAULTS.chainLimits().maxPayloadBytes()
		- EphemeralKey.SIZE;
	private static final String TOO_LONG = "g".repeat(LONGEST_FITTING_ID + 1);

	/* Draws that depend only on how many draws came before and how many bytes are asked for, so that the test
	   can rebuild what attempt number n drew: its fingerprint first, then its private key */
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
		return (fingerprint, excluded) -> List.of(members).stream().filter(member -> !excluded.contains(member))
			.findFirst();
	}

	private static void timeOut(SessionWire wire) {
		wire.clock.addAndGet(ClientSession.ACCEPT_TIMEOUT_MILLIS);
	}

	private static Fingerprint fingerprintOfAttempt(int attempt) {
		final byte[] bytes = new byte[Fingerprint.SIZE];

		new CountedRandom(2 * attempt).nextBytes(bytes);
		return new Fingerprint(bytes);
	}

	private static byte[] privateKeyOfAttempt(int attempt) {
		return new X25519PrivateKeyParameters(new CountedRandom(2 * attempt + 1)).getEncoded();
	}

	private static ClientSession clientOf(SessionWire wire, SecureRandom random, ExitSelector selector) {
		wire.client = ClientSessionTest.clientOf(wire, wire.posted::add, random, selector);
		return wire.client;
	}

	/* The exit's ACCEPT to an OPEN, taken off the wire before anyone else sees it */
	private static Entry acceptTo(SessionWire wire, Entry open, String exitId) {
		Session.accept(wire.exitContext, open, exitId).orElseThrow();
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
	public void shouldRetryWithANewFingerprintTowardsTheNextExit() {
		final SessionWire wire = new SessionWire("gn-c");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b", "gn-c"), CONNECT);
		final List<Fingerprint> fingerprints = new ArrayList<>();

		client.open();

		for (String silent : List.of("gn-a", "gn-b")) {
			wire.pump();
			assertThat(client.exit(), equalTo(Optional.of(silent)));
			assertThat(client.state(), is(SessionState.OPENING));
			fingerprints.add(client.fingerprint().orElseThrow());
			timeOut(wire);
			assertThat(client.tick(), equalTo(Optional.empty()));
		}

		wire.pump();
		fingerprints.add(client.fingerprint().orElseThrow());
		assertThat(client.exit(), equalTo(Optional.of("gn-c")));
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(new HashSet<>(fingerprints).size(), is(3));
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
	public void shouldIgnoreTheLateAcceptOfAnEarlierAttempt() {
		final SessionWire wire = new SessionWire("gn-a");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b"), CONNECT);

		client.open();

		final Fingerprint first = client.fingerprint().orElseThrow();

		timeOut(wire);
		client.tick();
		wire.pump();
		assertThat(wire.exits.keySet().contains(first), is(true));
		assertThat(client.fingerprint().orElseThrow(), not(equalTo(first)));
		assertThat(client.state(), is(SessionState.OPENING));
		assertThat(client.exit(), equalTo(Optional.of("gn-b")));
	}

	@Example
	public void shouldPassOverAnExitWhoseIdWouldNotFitTheOpenInOneEntry() {
		final SessionWire wire = new SessionWire("gn-b");
		final ClientSession client = wire.client(inOrder("g".repeat(LONGEST_FITTING_ID + 1), "gn-b"), CONNECT);

		assertThat(client.open(), is(true));
		wire.pump();
		assertThat(client.exit(), equalTo(Optional.of("gn-b")));
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldTakeAnExitWhoseIdFillsTheOpenExactly() {
		final String longest = "g".repeat(LONGEST_FITTING_ID);
		final SessionWire wire = new SessionWire(longest);
		final ClientSession client = wire.client(inOrder(longest), CONNECT);

		assertThat(client.open(), is(true));
		assertThat(wire.posted.peek().size(), is((int) ProxySettings.DEFAULTS.maxEntryBytes()));
		wire.pump();
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldNotOpenWhenNoExitHasAnIdThatFits() {
		final SessionWire wire = new SessionWire("gn-a");
		final ClientSession client = wire.client(inOrder("g".repeat(LONGEST_FITTING_ID + 1)), CONNECT);

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
		assertThat(client.fingerprint(), equalTo(Optional.empty()));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.open(), is(true));
	}

	@Example
	public void shouldNotRetryOnceTheHandshakeIsDone() {
		final SessionWire wire = new SessionWire("gn-a");
		final ClientSession client = wire.client(inOrder("gn-a", "gn-b"), CONNECT);

		client.open();
		wire.pump();

		final Fingerprint fingerprint = client.fingerprint().orElseThrow();

		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.state(), is(SessionState.OPEN));
		assertThat(client.fingerprint(), equalTo(Optional.of(fingerprint)));
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
		assertThat(client.receive(acceptTo(wire, wire.posted.poll(), "gn-a")),
			equalTo(Optional.of(new Close(CloseReason.BROKEN))));
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(posts.get(), is(2));
		assertThat(client.state(), is(SessionState.CLOSED));
	}

	@Example
	public void shouldDrawTheNewFingerprintAndKeyFromTheRandomnessOfItsContext() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = clientOf(wire, new CountedRandom(0), inOrder("gn-a", "gn-b", "gn-c"));

		client.open();
		timeOut(wire);
		client.tick();
		timeOut(wire);
		client.tick();

		for (int attempt = 0; attempt < ClientSession.MAX_ATTEMPTS; attempt++) {
			final Entry open = wire.posted.poll();

			assertThat(open.header().fingerprint(), equalTo(fingerprintOfAttempt(attempt)));
			assertThat(Handshake.clientKey(open.payload()),
				equalTo(new EphemeralKey(privateKeyOfAttempt(attempt)).publicKey()));
		}

		assertThat(client.fingerprint(), equalTo(Optional.of(fingerprintOfAttempt(2))));
	}

	@Example
	public void shouldChooseEachExitForItsFingerprintWithEveryExitTriedSoFarExcluded() {
		final SessionWire wire = new SessionWire("gn-z");
		final List<Fingerprint> asked = new ArrayList<>();
		final List<Set<String>> excluded = new ArrayList<>();
		final ExitSelector ordered = inOrder("gn-a", "gn-b", "gn-c");
		final ClientSession client = wire.client((fingerprint, passedOver) -> {
			asked.add(fingerprint);
			excluded.add(Set.copyOf(passedOver));
			return ordered.exit(fingerprint, passedOver);
		}, CONNECT);

		client.open();
		timeOut(wire);
		client.tick();
		timeOut(wire);
		client.tick();
		assertThat(asked, equalTo(wire.posted.stream().map(open -> open.header().fingerprint()).toList()));
		assertThat(excluded, equalTo(List.of(Set.of(), Set.of("gn-a"), Set.of("gn-a", "gn-b"))));
	}

	@Example
	public void shouldNeverPostMoreOpensThanTheMaximumWhateverTheSelectorAnswers() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client((fingerprint, excluded) -> Optional.of("gn-a"), CONNECT);
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
	public void shouldPassOverAnExitThatDoesNotFitWhenRetrying() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a", TOO_LONG, "gn-c"), CONNECT);

		client.open();
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(client.exit(), equalTo(Optional.of("gn-c")));
		assertThat(wire.posted.size(), is(2));
		assertThat(Handshake.names(wire.posted.peekLast().payload(), "gn-c"), is(true));
	}

	@Example
	public void shouldRetryTowardsAnExitWhoseIdFillsTheOpenExactly() {
		final String longest = "g".repeat(LONGEST_FITTING_ID);
		final SessionWire wire = new SessionWire(longest);
		final ClientSession client = wire.client(inOrder("gn-a", longest), CONNECT);

		client.open();
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.peekLast().size(), is((int) ProxySettings.DEFAULTS.maxEntryBytes()));
		wire.pump();
		assertThat(client.state(), is(SessionState.OPEN));
	}

	@Example
	public void shouldGiveUpWhenNoExitLeftHasAnIdThatFits() {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = wire.client(inOrder("gn-a", TOO_LONG), CONNECT);

		client.open();
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(client.state(), is(SessionState.CLOSED));
		assertThat(wire.posted.size(), is(1));
		assertThat(client.tick(), equalTo(Optional.empty()));
	}

	@Example
	public void shouldAskAgainUntilTheExitFitsWithEachExitThatDoesNotFitExcluded() {
		final String longer = TOO_LONG + "g";
		final String wide = "é".repeat(LONGEST_FITTING_ID / 2 + 1);
		final SessionWire wire = new SessionWire("gn-b");
		final List<Set<String>> excluded = new ArrayList<>();
		final ExitSelector ordered = inOrder(TOO_LONG, longer, wide, "gn-b");
		final ClientSession client = wire.client((fingerprint, passedOver) -> {
			excluded.add(Set.copyOf(passedOver));
			return ordered.exit(fingerprint, passedOver);
		}, CONNECT);

		assertThat(client.open(), is(true));
		assertThat(excluded, equalTo(List.of(Set.of(), Set.of(TOO_LONG), Set.of(TOO_LONG, longer),
			Set.of(TOO_LONG, longer, wide))));
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
		final Entry late = acceptTo(wire, first, "gn-a");

		assertThat(client.receive(late), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.OPENING));
		assertThat(client.exit(), equalTo(Optional.of("gn-b")));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(client.receive(acceptTo(wire, second, "gn-b")), equalTo(Optional.empty()));
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
	public void shouldForgetThePrivateKeyWhenNoExitLeftHasAnIdThatFits() throws IllegalAccessException {
		final SessionWire wire = new SessionWire("gn-z");
		final ClientSession client = clientOf(wire, new CountedRandom(0), inOrder("gn-a", TOO_LONG));

		client.open();
		timeOut(wire);
		assertThat(client.tick(), equalTo(Optional.of(new Close(CloseReason.UNREACHABLE))));
		assertThat(holdsNoKeyOfAnyAttempt(client, 2), is(true));
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
