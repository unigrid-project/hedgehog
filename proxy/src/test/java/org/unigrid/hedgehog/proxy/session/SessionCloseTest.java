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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.Gap;
import org.unigrid.hedgehog.proxy.group.ExitChoice;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class SessionCloseTest {
	private static final String EXIT_ID = "gn-exit";
	private static final ExitSelector ONLY_EXIT = (fingerprint, excluded) -> ExitChoice.exit(fingerprint,
		List.of(EXIT_ID), excluded);
	private static final long IDLE_MILLIS = ProxySettings.DEFAULTS.sessionIdleMillis();
	private static final long RETENTION_MILLIS = ProxySettings.DEFAULTS.chainLimits().retentionMillis();
	private static final long AN_HOUR = TimeUnit.HOURS.toMillis(1);
	private static final int WAIT_SECONDS = 10;
	/* The entries of a session that has run its handshake: the OPEN, the ACCEPT and the sealed Connect */
	private static final int HANDSHAKE_ENTRIES = 3;
	private static final Fingerprint OTHER_SESSION = SessionFixtures.fingerprint(9);
	private static final Connect CONNECT = new Connect(Socks5CommandType.CONNECT, new SocksAddress(
		Socks5AddressType.DOMAIN, "example.org", 443));

	private static SessionWire opened() {
		return opened(ProxySettings.DEFAULTS);
	}

	private static SessionWire opened(ProxySettings settings) {
		final SessionWire wire = new SessionWire(EXIT_ID, settings);

		wire.client(ONLY_EXIT, CONNECT).open();
		wire.pump();
		assertThat(wire.client.state(), is(SessionState.OPEN));
		return wire;
	}

	/* A client whose outbox refuses every entry while told to, after the session was opened over a wire */
	private static SessionWire openedOverAnOutbox(AtomicBoolean refusing) {
		final SessionWire wire = new SessionWire(EXIT_ID);

		wire.client = ClientSessionTest.clientOf(wire, entry -> {
			if (refusing.get()) {
				throw new IllegalStateException("The outbox refuses");
			}

			wire.posted.add(entry);
		}, new SecureRandom(), ONLY_EXIT);
		wire.client.open();
		wire.pump();
		assertThat(wire.client.state(), is(SessionState.OPEN));
		return wire;
	}

	private static ProxySettings idleAfter(int seconds) {
		final ProxySettings defaults = ProxySettings.DEFAULTS;

		return new ProxySettings(defaults.groupSize(), defaults.reorganizePeriodSeconds(), defaults.chainsPerGroup(),
			defaults.clientsPerChain(), defaults.chainCapacityBytes(), defaults.retentionSeconds(),
			defaults.maxEntryBytes(), seconds, defaults.flags());
	}

	private static Data data(int value) {
		return new Data(new byte[] { (byte) value });
	}

	private static Close close(CloseReason reason) {
		return new Close(reason);
	}

	/* The client sends data 1, 2 and 3 and the exit's replica never gets the second one */
	private static void sendThreeLosingTheSecond(SessionWire wire) {
		for (int i = 1; i <= 3; i++) {
			wire.client.send(data(i));
		}

		wire.lostAtExit.add(List.copyOf(wire.posted).get(1));
		wire.pump();
	}

	/* What the host does with the gaps its chain skipped: it closes the sessions they name with BROKEN */
	private static List<Optional<Close>> closeBrokenWhereNamed(List<Gap> gaps, Session session) {
		return gaps.stream().filter(gap -> gap.fingerprints().contains(session.fingerprint()))
			.map(gap -> session.close(CloseReason.BROKEN)).toList();
	}

	private static long sealedEntriesOf(SessionWire wire, SessionContext writer) {
		return wire.clientChain.entries().stream().filter(entry -> entry.header().kind() == EntryKind.SEALED
			&& entry.header().writer().equals(writer.writer().writer())).count();
	}

	private static void assertNeitherCloses(SessionWire wire) {
		assertThat(wire.exitSession().tick(), equalTo(Optional.empty()));
		assertThat(wire.client.tick(), equalTo(Optional.empty()));
	}

	private static void assertWaitsFor(Object monitor, Runnable action) throws InterruptedException {
		final Thread thread = new Thread(action);

		thread.setDaemon(true);

		synchronized (monitor) {
			thread.start();

			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

			while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED
				&& System.nanoTime() < deadline) {
				Thread.sleep(1);
			}

			assertThat(thread.getState(), is(Thread.State.BLOCKED));
		}

		thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
		assertThat(thread.isAlive(), is(false));
	}

	@Example
	public void shouldCloseBothSidesAfterTheIdleTime() {
		final SessionWire wire = opened();

		wire.clock.addAndGet(IDLE_MILLIS - 1);
		assertThat(wire.exitSession().tick(), equalTo(Optional.empty()));
		assertThat(wire.client.tick(), equalTo(Optional.empty()));
		wire.clock.addAndGet(1);
		assertThat(wire.exitSession().tick(), equalTo(Optional.of(new Close(CloseReason.IDLE))));
		wire.pump();
		assertThat(wire.atClient, equalTo(List.of(new Close(CloseReason.IDLE))));
		assertThat(wire.client.state(), is(SessionState.CLOSED));
		assertThat(wire.client.tick(), equalTo(Optional.empty()));
	}

	@Example
	public void shouldCountTrafficEitherWayAsActivity() {
		final SessionWire wire = opened();

		wire.clock.addAndGet(IDLE_MILLIS - 1);
		wire.exitSession().send(data(1));
		wire.pump();
		wire.clock.addAndGet(IDLE_MILLIS - 1);
		assertThat(wire.client.tick(), equalTo(Optional.empty()));
		assertThat(wire.exitSession().tick(), equalTo(Optional.empty()));
		wire.clock.addAndGet(1);
		assertThat(wire.client.tick(), equalTo(Optional.of(new Close(CloseReason.IDLE))));
		wire.pump();
		assertThat(wire.atExit, equalTo(List.of(CONNECT, new Close(CloseReason.IDLE))));
		assertThat(wire.exitSession().isOpen(), is(false));
	}

	@Example
	public void shouldTellTheExitWhenTheClientCloses() {
		final SessionWire wire = opened();

		assertThat(wire.client.close(CloseReason.DONE), equalTo(Optional.of(new Close(CloseReason.DONE))));
		assertThat(wire.client.close(CloseReason.DONE), equalTo(Optional.empty()));
		wire.pump();
		assertThat(wire.atExit, equalTo(List.of(CONNECT, new Close(CloseReason.DONE))));
		assertThat(wire.exitSession().isOpen(), is(false));
	}

	@Example
	public void shouldTellTheClientWhenTheExitCloses() {
		final SessionWire wire = opened();

		wire.exitSession().close(CloseReason.REFUSED);
		wire.pump();
		assertThat(wire.atClient, equalTo(List.of(new Close(CloseReason.REFUSED))));
		assertThat(wire.client.state(), is(SessionState.CLOSED));
		Expect.assertThrows(IllegalStateException.class, () -> wire.client.send(data(1)));
	}

	@Example
	public void shouldStayClosedWhenTheAcceptComesAfterClosing() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client((fingerprint, excluded) -> Optional.of(EXIT_ID), CONNECT);

		client.open();
		assertThat(client.close(CloseReason.DONE), equalTo(Optional.of(new Close(CloseReason.DONE))));
		wire.pump();
		assertThat(client.state(), is(SessionState.CLOSED));
		assertThat(wire.atExit, equalTo(List.of()));
		assertThat(client.tick(), equalTo(Optional.empty()));
	}

	@Example
	public void shouldBreakOnASkippedCounter() {
		final SessionWire wire = opened();
		final List<Entry> sent = new ArrayList<>();

		for (int i = 1; i <= 3; i++) {
			wire.client.send(data(i));
			sent.add(wire.posted.poll());
		}

		assertThat(wire.exitSession().receive(sent.get(0)), equalTo(Optional.of(data(1))));
		assertThat(wire.exitSession().receive(sent.get(2)), equalTo(Optional.of(new Close(CloseReason.BROKEN))));
		wire.pump();
		assertThat(wire.atClient, equalTo(List.of(new Close(CloseReason.BROKEN))));
		assertThat(wire.client.state(), is(SessionState.CLOSED));
	}

	@Example
	public void shouldBreakWhenTheChainSkipsAGapInTheSession() {
		final SessionWire wire = opened();

		for (int i = 1; i <= 3; i++) {
			wire.client.send(data(i));
		}

		wire.lostAtExit.add(List.copyOf(wire.posted).get(1));
		wire.pump();
		assertThat(wire.atExit, equalTo(List.of(CONNECT, data(1))));
		wire.clock.addAndGet(RETENTION_MILLIS + 1);

		final List<Gap> gaps = wire.exitChain.expireGaps();

		assertThat(gaps.size(), is(1));
		assertThat(gaps.get(0).fingerprints().contains(wire.client.fingerprint().orElseThrow()), is(true));
		wire.client.send(data(4));
		wire.pump();
		assertThat(wire.atExit, equalTo(List.of(CONNECT, data(1), new Close(CloseReason.BROKEN))));
		assertThat(wire.atClient, equalTo(List.of(new Close(CloseReason.BROKEN))));
	}

	@Example
	public void shouldCloseAtTheIdleTimeOfTheSettingAndNotOneMillisecondBefore() {
		for (int seconds : List.of(1, 2, 0xFFFF)) {
			final long idle = TimeUnit.SECONDS.toMillis(seconds);
			final SessionWire wire = opened(idleAfter(seconds));

			wire.clock.addAndGet(idle - 1);
			assertThat(wire.exitSession().tick(), equalTo(Optional.empty()));
			assertThat(wire.client.tick(), equalTo(Optional.empty()));
			wire.clock.addAndGet(1);
			assertThat(wire.client.tick(), equalTo(Optional.of(close(CloseReason.IDLE))));
			assertThat(wire.exitSession().tick(), equalTo(Optional.of(close(CloseReason.IDLE))));
		}
	}

	@Example
	public void shouldNotCloseWhileTheClockIsBehindTheLastTraffic() {
		final SessionWire wire = opened();

		wire.clock.addAndGet(-IDLE_MILLIS);
		assertNeitherCloses(wire);
		wire.clock.addAndGet(-AN_HOUR);
		assertNeitherCloses(wire);
		wire.clock.set(0);
		assertNeitherCloses(wire);
		wire.clock.set(SessionFixtures.NOW + IDLE_MILLIS - 1);
		assertNeitherCloses(wire);
		wire.clock.addAndGet(1);
		assertThat(wire.exitSession().tick(), equalTo(Optional.of(close(CloseReason.IDLE))));
		assertThat(wire.client.tick(), equalTo(Optional.of(close(CloseReason.IDLE))));
	}

	@Example
	public void shouldNotCountWhatItIgnoresOrRefusesAsActivity() {
		final SessionWire wire = opened();
		final Session exit = wire.exitSession();
		final Entry ofAnotherSession = wire.clientContext.post(OTHER_SESSION, EntryKind.SEALED, new byte[40]);

		wire.posted.clear();
		wire.clock.addAndGet(IDLE_MILLIS - 1);
		assertThat(exit.receive(ofAnotherSession), equalTo(Optional.empty()));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> exit.send(new Data(new byte[exit.maxDataBytes() + 1])));
		wire.clock.addAndGet(1);
		assertThat(exit.tick(), equalTo(Optional.of(close(CloseReason.IDLE))));
	}

	@Example
	public void shouldNotCountASendTheOutboxRefusedAsActivity() {
		final AtomicBoolean refusing = new AtomicBoolean();
		final SessionWire wire = openedOverAnOutbox(refusing);

		wire.clock.addAndGet(IDLE_MILLIS - 1);
		refusing.set(true);
		Expect.assertThrows(IllegalStateException.class, () -> wire.client.send(data(1)));
		refusing.set(false);
		wire.clock.addAndGet(1);
		assertThat(wire.client.tick(), equalTo(Optional.of(close(CloseReason.IDLE))));
	}

	@Example
	public void shouldCloseWhenTheOutboxRefusesTheIdleClose() {
		final AtomicBoolean refusing = new AtomicBoolean();
		final SessionWire wire = openedOverAnOutbox(refusing);

		wire.clock.addAndGet(IDLE_MILLIS);
		refusing.set(true);
		Expect.assertThrows(IllegalStateException.class, wire.client::tick);
		assertThat(wire.client.state(), is(SessionState.CLOSED));
		assertThat(wire.client.tick(), equalTo(Optional.empty()));
		assertThat(wire.client.close(CloseReason.DONE), equalTo(Optional.empty()));
	}

	@Example
	public void shouldTickAClosedSessionToNothing() {
		final SessionWire wire = opened();

		wire.client.close(CloseReason.DONE);
		wire.pump();
		wire.clock.addAndGet(10 * IDLE_MILLIS);
		assertThat(wire.client.tick(), equalTo(Optional.empty()));
		assertThat(wire.exitSession().tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(wire.exitChain.entries().size(), is(HANDSHAKE_ENTRIES + 1));
	}

	@Example
	public void shouldIdleOutTheExitOfAClientThatClosedBeforeTheAccept() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();
		client.close(CloseReason.DONE);
		wire.pump();
		assertThat(wire.exitSession().tick(), equalTo(Optional.empty()));
		wire.clock.addAndGet(IDLE_MILLIS);
		assertThat(wire.exitSession().tick(), equalTo(Optional.of(close(CloseReason.IDLE))));
		wire.pump();
		assertThat(wire.atClient, equalTo(List.of()));
		assertThat(client.state(), is(SessionState.CLOSED));
	}

	@Example
	public void shouldTellThePeerOnceWhateverTheReasonAndWhicheverSideCloses() {
		for (CloseReason reason : CloseReason.values()) {
			final SessionWire byClient = opened();
			final SessionWire byExit = opened();

			assertThat(byClient.client.close(reason), equalTo(Optional.of(close(reason))));
			assertThat(byClient.client.close(reason), equalTo(Optional.empty()));
			byClient.pump();
			assertThat(byClient.atExit, equalTo(List.of(CONNECT, close(reason))));
			assertThat(byExit.exitSession().close(reason), equalTo(Optional.of(close(reason))));
			assertThat(byExit.exitSession().close(reason), equalTo(Optional.empty()));
			byExit.pump();
			assertThat(byExit.atClient, equalTo(List.of(close(reason))));
			assertThat(byExit.client.state(), is(SessionState.CLOSED));
		}
	}

	@Example
	public void shouldNotAnswerTheCloseOfThePeer() {
		final SessionWire byClient = opened();
		final SessionWire byExit = opened();

		byClient.client.close(CloseReason.DONE);
		byClient.pump();
		byExit.exitSession().close(CloseReason.DONE);
		byExit.pump();

		for (SessionWire wire : List.of(byClient, byExit)) {
			assertThat(wire.posted.isEmpty(), is(true));
			assertThat(wire.clientChain.entries().size(), is(HANDSHAKE_ENTRIES + 1));
			assertThat(wire.exitChain.entries(), equalTo(wire.clientChain.entries()));
			assertThat(wire.client.close(CloseReason.DONE), equalTo(Optional.empty()));
			assertThat(wire.exitSession().close(CloseReason.DONE), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldIgnoreTheCloseOfThePeerWhenClosingToo() {
		final SessionWire wire = opened();

		wire.client.close(CloseReason.DONE);
		wire.exitSession().close(CloseReason.IDLE);
		wire.pump();
		assertThat(wire.atClient, equalTo(List.of()));
		assertThat(wire.atExit, equalTo(List.of(CONNECT)));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(wire.clientChain.entries().size(), is(HANDSHAKE_ENTRIES + 2));
	}

	@Example
	public void shouldIgnoreACloseOfThePeerThatArrivesAgain() {
		final SessionWire wire = opened();

		wire.exitSession().close(CloseReason.REFUSED);

		final Entry refused = wire.posted.peek();

		wire.pump();
		assertThat(wire.client.receive(refused), equalTo(Optional.empty()));
		assertThat(wire.posted.isEmpty(), is(true));
		assertThat(wire.atClient, equalTo(List.of(close(CloseReason.REFUSED))));
	}

	@Example
	public void shouldBreakTheClientOnASkippedCounter() {
		final SessionWire wire = opened();
		final List<Entry> sent = new ArrayList<>();

		for (int i = 1; i <= 3; i++) {
			wire.exitSession().send(data(i));
			sent.add(wire.posted.poll());
		}

		assertThat(wire.client.receive(sent.get(0)), equalTo(Optional.of(data(1))));
		assertThat(wire.client.receive(sent.get(2)), equalTo(Optional.of(close(CloseReason.BROKEN))));
		assertThat(wire.client.state(), is(SessionState.CLOSED));
		wire.pump();
		assertThat(wire.atExit, equalTo(List.of(CONNECT, close(CloseReason.BROKEN))));
		assertThat(wire.exitSession().isOpen(), is(false));
	}

	@Example
	public void shouldCloseOnceWhenTheHostClosesTheSessionNamedInAGap() {
		final SessionWire wire = opened();

		sendThreeLosingTheSecond(wire);
		wire.clock.addAndGet(RETENTION_MILLIS + 1);

		final List<Gap> gaps = wire.exitChain.expireGaps();

		assertThat(closeBrokenWhereNamed(gaps, wire.exitSession()), equalTo(List.of(Optional.of(
			close(CloseReason.BROKEN)))));
		assertThat(closeBrokenWhereNamed(gaps, wire.exitSession()), equalTo(List.of(Optional.empty())));
		wire.client.send(data(4));
		wire.pump();
		assertThat(wire.atExit, equalTo(List.of(CONNECT, data(1))));
		assertThat(wire.atClient, equalTo(List.of(close(CloseReason.BROKEN))));
		assertThat(wire.client.state(), is(SessionState.CLOSED));
		assertThat(sealedEntriesOf(wire, wire.exitContext), is(1L));
	}

	@Example
	public void shouldKeepTheSessionWhenTheGapNamesOtherSessions() {
		final SessionWire wire = opened();

		wire.client.send(data(1));
		wire.pump();
		wire.lostAtExit.add(wire.clientContext.post(OTHER_SESSION, EntryKind.SEALED, new byte[40]));
		wire.clientContext.post(OTHER_SESSION, EntryKind.SEALED, new byte[41]);
		wire.pump();
		wire.clock.addAndGet(RETENTION_MILLIS + 1);

		final List<Gap> gaps = wire.exitChain.expireGaps();

		assertThat(gaps.size(), is(1));
		assertThat(gaps.get(0).fingerprints(), equalTo(Set.of(OTHER_SESSION)));
		assertThat(closeBrokenWhereNamed(gaps, wire.exitSession()), equalTo(List.of()));
		wire.client.send(data(2));
		wire.pump();
		assertThat(wire.atExit, equalTo(List.of(CONNECT, data(1), data(2))));
		assertThat(wire.exitSession().isOpen(), is(true));
		assertThat(wire.client.state(), is(SessionState.OPEN));
		assertThat(wire.atClient, equalTo(List.of()));
	}

	@Example
	public void shouldBreakOnTheNextEntryWhenTheGapDoesNotNameTheSessionThatLostOne() {
		final SessionWire wire = opened();

		wire.client.send(data(1));
		wire.client.send(data(2));
		wire.lostAtExit.add(List.copyOf(wire.posted).get(1));
		wire.clientContext.post(OTHER_SESSION, EntryKind.SEALED, new byte[40]);
		wire.pump();
		wire.clock.addAndGet(RETENTION_MILLIS + 1);

		final List<Gap> gaps = wire.exitChain.expireGaps();

		assertThat(gaps.size(), is(1));
		assertThat(gaps.get(0).fingerprints(), equalTo(Set.of(OTHER_SESSION)));
		assertThat(closeBrokenWhereNamed(gaps, wire.exitSession()), equalTo(List.of()));
		assertThat(wire.exitSession().isOpen(), is(true));
		wire.client.send(data(3));
		wire.pump();
		assertThat(wire.atExit, equalTo(List.of(CONNECT, data(1), close(CloseReason.BROKEN))));
		assertThat(wire.atClient, equalTo(List.of(close(CloseReason.BROKEN))));
	}

	@Example
	public void shouldCloseTheClientBeforeItsFirstOpen() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		assertThat(client.close(CloseReason.DONE), equalTo(Optional.of(close(CloseReason.DONE))));
		assertThat(client.close(CloseReason.DONE), equalTo(Optional.empty()));
		assertThat(client.state(), is(SessionState.CLOSED));
		Expect.assertThrows(IllegalStateException.class, client::open);
		assertThat(wire.posted.isEmpty(), is(true));
	}

	@Example
	public void shouldNameTheReasonWhenTheClientClosesBeforeAnExitAnswered() {
		for (CloseReason reason : CloseReason.values()) {
			final SessionWire opening = new SessionWire(EXIT_ID);
			final SessionWire unopened = new SessionWire(EXIT_ID);

			opening.client(ONLY_EXIT, CONNECT).open();
			assertThat(opening.client.close(reason), equalTo(Optional.of(close(reason))));
			assertThat(unopened.client(ONLY_EXIT, CONNECT).close(reason), equalTo(Optional.of(close(reason))));
		}
	}

	@Example
	public void shouldNeverRetryAnOpenOnceTheClientClosedIt() {
		final SessionWire wire = new SessionWire(EXIT_ID);
		final ClientSession client = wire.client(ONLY_EXIT, CONNECT);

		client.open();
		assertThat(client.close(CloseReason.DONE), equalTo(Optional.of(close(CloseReason.DONE))));
		assertThat(client.close(CloseReason.IDLE), equalTo(Optional.empty()));
		wire.clock.addAndGet(ClientSession.MAX_ATTEMPTS * ClientSession.ACCEPT_TIMEOUT_MILLIS);
		assertThat(client.tick(), equalTo(Optional.empty()));
		assertThat(wire.posted.size(), is(1));
		assertThat(client.state(), is(SessionState.CLOSED));
	}

	@Example
	public void shouldNotCloseAClientThatGaveUpOrFoundNoExit() {
		final SessionWire none = new SessionWire(EXIT_ID);
		final ClientSession withoutExit = none.client((fingerprint, excluded) -> Optional.empty(), CONNECT);
		final SessionWire silent = new SessionWire(EXIT_ID);
		final ClientSession unanswered = silent.client((fingerprint, excluded) -> Optional.of("gn-silent"), CONNECT);

		assertThat(withoutExit.open(), is(false));
		assertThat(withoutExit.close(CloseReason.DONE), equalTo(Optional.empty()));
		unanswered.open();

		for (int attempt = 0; attempt < ClientSession.MAX_ATTEMPTS; attempt++) {
			silent.clock.addAndGet(ClientSession.ACCEPT_TIMEOUT_MILLIS);
			unanswered.tick();
		}

		assertThat(unanswered.state(), is(SessionState.CLOSED));
		assertThat(unanswered.close(CloseReason.DONE), equalTo(Optional.empty()));
	}

	@Example
	public void shouldWaitForTheSessionWhenTickingOrClosing() throws InterruptedException {
		final SessionWire established = opened();
		final SessionWire opening = new SessionWire(EXIT_ID);

		opening.client(ONLY_EXIT, CONNECT).open();
		assertWaitsFor(established.exitSession(), () -> established.exitSession().tick());
		assertWaitsFor(established.client, established.client::tick);
		assertWaitsFor(opening.client, opening.client::tick);
		assertWaitsFor(opening.client, () -> opening.client.close(CloseReason.DONE));
		assertThat(opening.client.state(), is(SessionState.CLOSED));
	}
}
