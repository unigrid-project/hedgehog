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

package org.unigrid.hedgehog.service.chain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.Getter;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.Close;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.Connected;
import org.unigrid.hedgehog.proxy.session.Data;
import org.unigrid.hedgehog.proxy.session.Datagram;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import org.unigrid.hedgehog.proxy.socks.AssociationListener;
import org.unigrid.hedgehog.proxy.socks.AssociationStream;
import org.unigrid.hedgehog.proxy.socks.TunnelFailure;

public class ChainAssociationTest {
	private static final SocksAddress REMOTE = new SocksAddress(Socks5AddressType.IPv4, "192.0.2.53", 53);
	private static final SocksAddress ANSWERING = new SocksAddress(Socks5AddressType.IPv6, "2001:db8::35", 5353);
	private static final SocksAddress NAMED = new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 443);
	private static final Connected CONNECTED = new Connected(Socks5CommandStatus.SUCCESS, SocksAddress.UNSPECIFIED);
	private static final String OPENED = "opened";
	private static final int MAX_DATA = 100;
	private static final int WAIT_SECONDS = 10;

	/* What each address takes of an entry in front of the datagram: its type, the address and the port */
	private static final Map<SocksAddress, Integer> ADDRESS_BYTES = Map.of(REMOTE, 7, ANSWERING, 19, NAMED, 15);

	/* The session the tunnel opened, as the chain would see it. It takes whatever it is sent until a test says
	   otherwise, so that only the association decides what reaches it, and notes a call made under the lock of the
	   association. */
	private static final class FakeSession implements SessionHandle, SessionOpener {
		private final List<SealedMessage> sent = new CopyOnWriteArrayList<>();
		private final List<CloseReason> closes = new CopyOnWriteArrayList<>();
		private Consumer<SessionListener> opening = listener -> { };
		private RuntimeException refusal;
		private volatile Connect connect;
		private volatile Optional<String> country;
		private volatile SessionListener listener;
		@Getter(onMethod_ = @Override) private volatile boolean writable = true;
		private volatile boolean underLock;
		private int maxData = MAX_DATA;
		private int opens;

		@Override
		public SessionHandle open(final Connect connect, final Optional<String> country,
			final SessionListener listener) {

			this.connect = connect;
			this.country = country;
			this.listener = listener;
			opens++;
			opening.accept(listener);
			return this;
		}

		private void called() {
			underLock |= listener != null && Thread.holdsLock(listener);
		}

		@Override
		public void send(final SealedMessage message) {
			called();

			if (refusal != null) {
				throw refusal;
			}

			sent.add(message);
		}

		@Override
		public int maxDataBytes() {
			return maxData;
		}

		@Override
		public void close(final CloseReason reason) {
			called();
			closes.add(reason);
		}
	}

	/* What the front end hears, and whether it heard anything while the lock of its association was held */
	private final class Heard implements AssociationListener {
		private final List<String> events = new CopyOnWriteArrayList<>();
		private Consumer<String> reaction = event -> { };
		private volatile boolean underLock;

		private void heard(final String event) {
			final SessionListener association = session.listener;

			underLock |= association != null && Thread.holdsLock(association);
			events.add(event);
			reaction.accept(event);
		}

		@Override
		public void opened() {
			heard(OPENED);
		}

		@Override
		public void failed(final TunnelFailure failure) {
			heard("failed " + failure);
		}

		@Override
		public void received(final SocksAddress source, final byte[] bytes) {
			heard(receivedFrom(source, bytes));
		}

		@Override
		public void closed() {
			heard("closed");
		}
	}

	/* Runs the deadlines the tunnel sets when a test moves the time on, and never by itself */
	private static final class Timers extends ScheduledThreadPoolExecutor {
		private final List<Timer> set = new ArrayList<>();
		private long now;

		private Timers() {
			super(1);
		}

		@Override
		public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
			final Timer timer = new Timer(command, now + unit.toMillis(delay));

			set.add(timer);
			return timer;
		}

		private void advance(final long millis) {
			now += millis;
			List.copyOf(set).stream().filter(timer -> timer.due <= now).forEach(Timer::run);
		}

		private Timer only() {
			assertThat(set.size(), is(1));
			return set.get(0);
		}
	}

	/* A cancelled timer does not run, as in a real scheduler, unless a test runs its command as one that had
	   started before the cancel */
	private static final class Timer extends FutureTask<Void> implements ScheduledFuture<Void> {
		private final Runnable command;
		private final long due;

		private Timer(final Runnable command, final long due) {
			super(command, null);
			this.command = command;
			this.due = due;
		}

		@Override
		public long getDelay(final TimeUnit unit) {
			return unit.convert(due, TimeUnit.MILLISECONDS);
		}

		@Override
		public int compareTo(final Delayed other) {
			return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
		}
	}

	/* The ways an association ends, with what the front end and the session heard of it */
	private enum Ending {
		REFUSED(List.of("failed HOST_UNREACHABLE"), List.of(CloseReason.DONE)),
		UNANSWERED(List.of("failed NETWORK_UNREACHABLE"), List.of()),
		EXPIRED(List.of("failed TTL_EXPIRED"), List.of(CloseReason.DONE)),
		ABANDONED(List.of(), List.of(CloseReason.DONE)),
		FINISHED(List.of(OPENED, "closed"), List.of()),
		CLOSED(List.of(OPENED), List.of(CloseReason.DONE));

		private final List<String> heard;
		private final List<CloseReason> closes;

		Ending(final List<String> heard, final List<CloseReason> closes) {
			this.heard = heard;
			this.closes = closes;
		}
	}

	private FakeSession session;
	private Heard heard;
	private Timers timers;
	private ChainTunnel tunnel;

	/* A property runs all its tries on one instance of the class, so every try starts from fresh state */
	@BeforeTry
	void fresh() {
		session = new FakeSession();
		heard = new Heard();
		timers = new Timers();
		tunnel = new ChainTunnel(() -> Optional.of(ProxySettings.DEFAULTS), session, timers);
	}

	@AfterTry
	void calledNothingUnderTheLockOfTheAssociation() {
		assertThat(heard.underLock, is(false));
		assertThat(session.underLock, is(false));
	}

	private static String receivedFrom(final SocksAddress source, final byte[] bytes) {
		return "received " + source + " " + HexFormat.of().formatHex(bytes);
	}

	private static ProxySettings allowing(final int flags) {
		return new ProxySettings(8, 3600, 4, 16, 32L * 1024 * 1024, 30, 16 * 1024, 300, flags);
	}

	private AssociationStream opened() {
		final AssociationStream association = tunnel.associate(Optional.empty(), heard);

		session.listener.received(CONNECTED);
		return association;
	}

	private AssociationStream ended(final Ending ending) {
		final AssociationStream association = ending.heard.contains(OPENED) ? opened() : tunnel.associate(Optional.empty(), heard);

		switch (ending) {
			case REFUSED -> session.listener.received(new Connected(Socks5CommandStatus.HOST_UNREACHABLE,
				SocksAddress.UNSPECIFIED));
			case UNANSWERED -> session.listener.received(new Close(CloseReason.UNREACHABLE));
			case EXPIRED -> timers.advance(ChainTunnel.DEADLINE_MILLIS);
			case FINISHED -> session.listener.received(new Close(CloseReason.DONE));
			default -> association.close();
		}

		return association;
	}

	/* Everything the session, the deadline and the front end can still do to an association, on a session that
	   would still take what it is sent */
	private void prodAfterTheEnd(final AssociationStream association) {
		session.listener.received(CONNECTED);
		session.listener.received(new Connected(Socks5CommandStatus.FAILURE, SocksAddress.UNSPECIFIED));
		session.listener.received(new Datagram(ANSWERING, new byte[] { 1 }));
		session.listener.writabilityChanged(false);
		session.listener.writabilityChanged(true);
		session.listener.received(new Close(CloseReason.IDLE));
		assertThat(association.send(REMOTE, new byte[] { 1 }), is(false));
		assertThat(association.send(REMOTE, new byte[0]), is(false));
		association.close();
		timers.advance(ChainTunnel.DEADLINE_MILLIS);
	}

	private static void meet(final CyclicBarrier barrier) {
		try {
			barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/* Starts the calls while the test holds the lock of the association and lets them go once each waits for it */
	private void blockedOnTheAssociation(final List<Runnable> calls) throws InterruptedException {
		final List<Thread> waiting = calls.stream().map(Thread::new).toList();

		synchronized (session.listener) {
			final List<String> before = List.copyOf(heard.events);

			waiting.forEach(Thread::start);
			await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> waiting.stream()
				.allMatch(thread -> thread.getState() == Thread.State.BLOCKED));
			assertThat(heard.events, equalTo(before));
		}

		for (Thread thread : waiting) {
			thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
		}
	}

	/* What the tunnel warns about while the action runs */
	private static List<ILoggingEvent> warningsWhile(final Runnable action) {
		ChainAssociationTest.class.getModule().addReads(ModuleLayer.boot().findModule("ch.qos.logback.core")
			.orElseThrow());

		final Logger logger = (Logger) LoggerFactory.getLogger(ChainTunnel.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();
		logger.addAppender(appender);

		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
		}

		return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
	}

	/* The message of a failure may quote what the association carries, so only its class is logged */
	private static void loggedByClassAlone(final List<ILoggingEvent> warnings, final String quoted) {
		warnings.forEach(warning -> {
			assertThat(warning.getFormattedMessage(), containsString(IllegalStateException.class.getName()));
			assertThat(warning.getFormattedMessage(), not(containsString(quoted)));
			assertThat(warning.getThrowableProxy(), is(nullValue()));
		});
	}

	@Example
	public void passesTheCountryToTheSession() {
		tunnel.associate(Optional.of("NO"), heard);
		assertThat(session.country, equalTo(Optional.of("NO")));
	}

	@Example
	public void opensASessionThatAssociatesAndReportsWhenTheExitIsReady() {
		tunnel.associate(Optional.empty(), heard);

		assertThat(session.connect, equalTo(new Connect(Socks5CommandType.UDP_ASSOCIATE, SocksAddress.UNSPECIFIED)));
		assertThat(session.opens, is(1));
		assertThat(heard.events, empty());
		assertThat(timers.only().isDone(), is(false));

		session.listener.received(CONNECTED);
		assertThat(heard.events, equalTo(List.of(OPENED)));
		assertThat(timers.only().isCancelled(), is(true));
	}

	@Property(tries = 8)
	public void failsWithTheReplyTheExitGaveAndIgnoresTheCloseThatFollows(@ForAll @IntRange(min = 1, max = 8) int code) {
		final Socks5CommandStatus status = Socks5CommandStatus.valueOf((byte) code);

		tunnel.associate(Optional.empty(), heard);
		session.listener.received(new Connected(status, SocksAddress.UNSPECIFIED));
		session.listener.received(new Close(CloseReason.REFUSED));

		assertThat(heard.events, equalTo(List.of("failed " + TunnelFailure.of(status))));
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	/* An exit that answers with a failure, sends no Close and keeps sending would keep the session alive for as long
	   as it likes, as the front end hangs up on an association that has ended already */
	@Example
	public void closesTheSessionOfAnAssociationTheExitRefusedThoughTheExitDoesNot() {
		final AssociationStream association = tunnel.associate(Optional.empty(), heard);

		session.listener.received(new Connected(Socks5CommandStatus.HOST_UNREACHABLE, SocksAddress.UNSPECIFIED));
		association.close();
		session.listener.received(new Datagram(ANSWERING, new byte[] { 1 }));
		session.listener.received(new Connected(Socks5CommandStatus.FAILURE, SocksAddress.UNSPECIFIED));

		assertThat(heard.events, equalTo(List.of("failed HOST_UNREACHABLE")));
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	@Example
	public void closesTheSessionOfAnAssociationRefusedBeforeItsHandleCameBack() {
		session.opening = listener -> listener.received(new Connected(Socks5CommandStatus.CONNECTION_REFUSED,
			SocksAddress.UNSPECIFIED));
		tunnel.associate(Optional.empty(), heard);

		assertThat(heard.events, equalTo(List.of("failed CONNECTION_REFUSED")));
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	@Property(tries = 10)
	public void failsWithTheReasonOfASessionThatClosedBeforeItsReply(@ForAll CloseReason reason) {
		tunnel.associate(Optional.empty(), heard);
		session.listener.received(new Close(reason));
		session.listener.received(new Close(reason));

		assertThat(heard.events, equalTo(List.of("failed " + TunnelFailure.of(reason))));
		assertThat(session.closes, empty());
	}

	@Example
	public void tellsTheReplyOnceAndNoFailureAfterIt() {
		opened();
		session.listener.received(CONNECTED);
		session.listener.received(new Connected(Socks5CommandStatus.HOST_UNREACHABLE, SocksAddress.UNSPECIFIED));
		session.listener.received(new Close(CloseReason.UNREACHABLE));
		session.listener.received(new Close(CloseReason.DONE));

		assertThat(heard.events, equalTo(List.of(OPENED, "closed")));
	}

	@Example
	public void handsOnTheDatagramsOfTheExitWithTheirSourcesAndNothingElse() {
		opened();
		session.listener.received(new Datagram(ANSWERING, new byte[] { 1, 2, 3 }));
		session.listener.received(new Data(new byte[] { 9 }));
		session.listener.received(new Connect(Socks5CommandType.UDP_ASSOCIATE, REMOTE));
		session.listener.received(new Datagram(NAMED, new byte[0]));

		assertThat(heard.events, equalTo(List.of(OPENED, receivedFrom(ANSWERING, new byte[] { 1, 2, 3 }),
			receivedFrom(NAMED, new byte[0]))));
	}

	@Provide
	Arbitrary<List<Datagram>> datagrams() {
		return Combinators.combine(Arbitraries.of(ADDRESS_BYTES.keySet()),
			Arbitraries.bytes().array(byte[].class).ofMaxSize(40)).as(Datagram::new).list().ofMaxSize(20);
	}

	@Property(tries = 50)
	public void relaysEveryDatagramOfTheExitOnceAndInOrder(@ForAll("datagrams") List<Datagram> datagrams) {
		final List<String> expected = new ArrayList<>(List.of(OPENED));

		opened();
		datagrams.forEach(datagram -> {
			session.listener.received(datagram);
			expected.add(receivedFrom(datagram.address(), datagram.bytes()));
		});

		session.listener.received(new Close(CloseReason.IDLE));
		expected.add("closed");
		assertThat(heard.events, equalTo(expected));
	}

	/* The exit relays nothing before it has set the association up, so what comes earlier is not its to send */
	@Example
	public void dropsTheDatagramsOfTheExitBeforeItsReply() {
		tunnel.associate(Optional.empty(), heard);
		session.listener.received(new Datagram(ANSWERING, new byte[] { 1 }));
		session.listener.received(CONNECTED);
		session.listener.received(new Datagram(ANSWERING, new byte[] { 2 }));

		assertThat(heard.events, equalTo(List.of(OPENED, receivedFrom(ANSWERING, new byte[] { 2 }))));
	}

	@Example
	public void sendsEachDatagramAsOneSealedMessage() {
		final AssociationStream association = opened();

		assertThat(association.send(REMOTE, new byte[] { 1, 2 }), is(true));
		assertThat(association.send(NAMED, new byte[] { 3 }), is(true));
		assertThat(session.sent, equalTo(List.of(new Datagram(REMOTE, new byte[] { 1, 2 }),
			new Datagram(NAMED, new byte[] { 3 }))));
	}

	/* An entry carries one Data message of maxDataBytes, and a datagram puts its address where Data has none: an
	   empty datagram is taken once its address fits, and nothing while the session carries no data */
	@Example
	public void takesExactlyTheDatagramsThatFitOneEntry() {
		final AssociationStream association = opened();

		for (int maxData = 0; maxData <= 40; maxData++) {
			for (int length = 0; length <= 40; length++) {
				for (Map.Entry<SocksAddress, Integer> address : ADDRESS_BYTES.entrySet()) {
					final boolean fits = address.getValue() + length <= maxData;

					session.sent.clear();
					session.maxData = maxData;
					assertThat(association.send(address.getKey(), new byte[length]), is(fits));
					assertThat(session.sent, equalTo(fits ? List.of(new Datagram(address.getKey(),
						new byte[length])) : List.of()));
				}
			}
		}
	}

	@Example
	public void dropsDatagramsBeforeTheExitRepliedAndKeepsNoneForLater() {
		final AssociationStream association = tunnel.associate(Optional.empty(), heard);

		assertThat(association.send(REMOTE, new byte[] { 1 }), is(false));
		session.listener.received(CONNECTED);

		assertThat(session.sent, empty());
	}

	@Example
	public void dropsDatagramsWhileTheChainHasNoRoomAndKeepsNoneForLater() {
		final AssociationStream association = opened();

		session.writable = false;
		session.listener.writabilityChanged(false);
		assertThat(association.send(REMOTE, new byte[] { 1 }), is(false));

		session.writable = true;
		session.listener.writabilityChanged(true);
		assertThat(session.sent, empty());
		assertThat(association.send(REMOTE, new byte[] { 2 }), is(true));
		assertThat(session.sent, equalTo(List.of(new Datagram(REMOTE, new byte[] { 2 }))));
		assertThat(heard.events, equalTo(List.of(OPENED)));
	}

	@Example
	public void dropsADatagramTheEndedSessionRefusesAndLeavesTheEndToItsClose() {
		final AssociationStream association = opened();

		session.refusal = new IllegalStateException("The session has ended");
		assertThat(association.send(REMOTE, new byte[] { 1 }), is(false));
		session.listener.received(new Close(CloseReason.IDLE));

		assertThat(heard.events, equalTo(List.of(OPENED, "closed")));
		assertThat(session.closes, empty());
	}

	@Example
	public void leavesADatagramTheSessionCannotFitToTheFrontEnd() {
		final AssociationStream association = opened();
		final IllegalArgumentException tooLarge = new IllegalArgumentException("Too large");

		session.refusal = tooLarge;

		try {
			association.send(REMOTE, new byte[] { 1 });
			throw new AssertionError("The send went through");
		} catch (IllegalArgumentException ex) {
			assertThat(ex, is(tooLarge));
		}
	}

	@Example
	public void tellsTheExitWhenTheFrontEndCloses() {
		final AssociationStream association = opened();

		association.close();
		association.close();
		session.listener.received(new Close(CloseReason.DONE));

		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
		assertThat(heard.events, equalTo(List.of(OPENED)));
	}

	@Property(tries = 6)
	public void endsOnceAndTellsNothingMore(@ForAll Ending ending) {
		final AssociationStream association = ended(ending);

		assertThat(heard.events, equalTo(ending.heard));
		assertThat(session.closes, equalTo(ending.closes));
		assertThat(timers.only().isDone(), is(true));

		prodAfterTheEnd(association);

		assertThat(heard.events, equalTo(ending.heard));
		assertThat(session.closes, equalTo(ending.closes));
		assertThat(session.sent, empty());
	}

	@Example
	public void tellsNothingThatWaitedOnceTheFrontEndClosed() {
		final AssociationStream association = opened();

		heard.reaction = event -> {
			session.listener.received(new Datagram(ANSWERING, new byte[] { 2 }));
			association.close();
		};

		session.listener.received(new Datagram(ANSWERING, new byte[] { 1 }));

		assertThat(heard.events, equalTo(List.of(OPENED, receivedFrom(ANSWERING, new byte[] { 1 }))));
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	@Example
	public void letsTheListenerCallBackIntoItsAssociation() {
		final AtomicReference<AssociationStream> association = new AtomicReference<>();
		final List<Boolean> taken = new ArrayList<>();

		heard.reaction = event -> {
			if ("closed".equals(event)) {
				association.get().close();
			} else {
				taken.add(association.get().send(REMOTE, new byte[] { (byte) taken.size() }));
			}
		};

		association.set(tunnel.associate(Optional.empty(), heard));
		session.listener.received(CONNECTED);
		session.listener.received(new Datagram(ANSWERING, new byte[] { 9 }));
		session.listener.received(new Close(CloseReason.DONE));

		assertThat(taken, equalTo(List.of(true, true)));
		assertThat(session.sent, equalTo(List.of(new Datagram(REMOTE, new byte[] { 0 }),
			new Datagram(REMOTE, new byte[] { 1 }))));
		assertThat(heard.events, equalTo(List.of(OPENED, receivedFrom(ANSWERING, new byte[] { 9 }), "closed")));
		assertThat(session.closes, empty());
	}

	@Example
	public void letsTheListenerCloseAnAssociationThatFailed() {
		final AtomicReference<AssociationStream> association = new AtomicReference<>();

		heard.reaction = event -> association.get().close();
		association.set(tunnel.associate(Optional.empty(), heard));
		session.listener.received(new Close(CloseReason.UNREACHABLE));

		assertThat(heard.events, equalTo(List.of("failed NETWORK_UNREACHABLE")));
		assertThat(session.closes, empty());
	}

	@Example
	public void goesOnWhenTheListenerThrows() {
		heard.reaction = event -> {
			throw new IllegalStateException("The front end failed with " + REMOTE);
		};

		final List<ILoggingEvent> warnings = warningsWhile(() -> {
			opened();
			session.listener.received(new Datagram(ANSWERING, new byte[] { 1 }));
			session.listener.received(new Datagram(ANSWERING, new byte[] { 2 }));
			session.listener.received(new Close(CloseReason.DONE));
		});

		assertThat(heard.events, equalTo(List.of(OPENED, receivedFrom(ANSWERING, new byte[] { 1 }),
			receivedFrom(ANSWERING, new byte[] { 2 }), "closed")));
		assertThat(warnings.size(), is(4));
		loggedByClassAlone(warnings, REMOTE.host());
	}

	@Example
	public void expiresAnAssociationThatNoExitAnswersInTime() {
		tunnel.associate(Optional.empty(), heard);
		timers.advance(ChainTunnel.DEADLINE_MILLIS - 1);
		assertThat(heard.events, empty());
		assertThat(session.closes, empty());

		timers.advance(1);
		assertThat(heard.events, equalTo(List.of("failed TTL_EXPIRED")));
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	@Example
	public void leavesAnAssociationThatGotItsReplyToItsSession() {
		opened();
		assertThat(timers.only().isCancelled(), is(true));

		timers.only().command.run();
		timers.advance(ChainTunnel.DEADLINE_MILLIS + 1);

		assertThat(heard.events, equalTo(List.of(OPENED)));
		assertThat(session.closes, empty());
	}

	/* A deadline that had started when the association ended runs its command all the same */
	@Example
	public void tellsNothingWhenADeadlineThatHadStartedRunsAfterTheAssociationWasClosed() {
		final AssociationStream association = tunnel.associate(Optional.empty(), heard);
		final Runnable command = timers.only().command;

		association.close();
		command.run();

		assertThat(heard.events, empty());
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	@Example
	public void tellsNothingMoreWhenADeadlineThatHadStartedRunsAfterTheSessionFailed() {
		tunnel.associate(Optional.empty(), heard);

		final Runnable command = timers.only().command;

		session.listener.received(new Close(CloseReason.UNREACHABLE));
		command.run();

		assertThat(heard.events, equalTo(List.of("failed NETWORK_UNREACHABLE")));
		assertThat(session.closes, empty());
	}

	@Example
	public void closesTheSessionOfAnAssociationThatExpiredBeforeItsHandleCameBack() {
		session.opening = listener -> timers.advance(ChainTunnel.DEADLINE_MILLIS);
		tunnel.associate(Optional.empty(), heard);

		assertThat(heard.events, equalTo(List.of("failed TTL_EXPIRED")));
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	@Property(tries = 100)
	public void letsTheReplyOrTheDeadlineWinButNeverBoth() throws InterruptedException {
		final CyclicBarrier start = new CyclicBarrier(2);
		final Thread exit = new Thread(() -> {
			meet(start);
			session.listener.received(CONNECTED);
		});

		tunnel.associate(Optional.empty(), heard);
		exit.start();
		meet(start);
		timers.only().command.run();
		exit.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

		assertThat(heard.events, either(equalTo(List.of(OPENED))).or(equalTo(List.of("failed TTL_EXPIRED"))));
		assertThat(session.closes, equalTo(heard.events.get(0).equals(OPENED) ? List.<CloseReason>of()
			: List.of(CloseReason.DONE)));
	}

	/* Whichever ends the association first, the front end hears of the end at most once, and the session is closed
	   only when the front end came first */
	@Property(tries = 100)
	public void endsOnceWhenTheExitAndTheFrontEndCloseTogether() throws InterruptedException {
		final AssociationStream association = opened();
		final CyclicBarrier start = new CyclicBarrier(2);
		final Thread exit = new Thread(() -> {
			meet(start);
			session.listener.received(new Close(CloseReason.DONE));
		});

		exit.start();
		meet(start);
		association.close();
		exit.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

		assertThat(heard.events.stream().filter("closed"::equals).count() + session.closes.size(),
			lessThanOrEqualTo(1L));
		assertThat(session.closes, either(empty()).or(equalTo(List.of(CloseReason.DONE))));
	}

	@Example
	public void decidesBetweenTheReplyAndTheDeadlineUnderTheLockOfTheAssociation() throws InterruptedException {
		tunnel.associate(Optional.empty(), heard);
		blockedOnTheAssociation(List.of(() -> session.listener.received(CONNECTED), timers.only().command));

		assertThat(heard.events, either(equalTo(List.of(OPENED))).or(equalTo(List.of("failed TTL_EXPIRED"))));
	}

	@Example
	public void decidesWhatTheExitAndTheFrontEndDoUnderTheLockOfTheAssociation() throws InterruptedException {
		final AssociationStream association = opened();

		blockedOnTheAssociation(List.of(() -> session.listener.received(new Datagram(ANSWERING, new byte[] { 1 })),
			() -> session.listener.received(new Close(CloseReason.DONE)), association::close));

		assertThat(heard.events.get(0), equalTo(OPENED));
		assertThat(heard.events.size(), lessThanOrEqualTo(3));
	}

	@Example
	public void isNotAllowedWhileTheSporkIsAbsentOrLacksTheUdpFlag() {
		final AtomicReference<Optional<ProxySettings>> spork = new AtomicReference<>();
		final ChainTunnel following = new ChainTunnel(spork::get, session, timers);
		final List<List<String>> heardPerAssociation = new ArrayList<>();

		for (Optional<ProxySettings> current : List.of(Optional.<ProxySettings>empty(), Optional.of(allowing(0)),
			Optional.of(allowing(ProxySettings.CONNECT)), Optional.of(allowing(ProxySettings.UDP)),
			Optional.of(ProxySettings.DEFAULTS))) {

			final Heard listener = new Heard();

			spork.set(current);
			following.associate(Optional.empty(), listener);
			heardPerAssociation.add(listener.events);
		}

		assertThat(heardPerAssociation, equalTo(List.of(List.of("failed NOT_ALLOWED"), List.of("failed NOT_ALLOWED"),
			List.of("failed NOT_ALLOWED"), List.of(), List.of())));
		assertThat(session.opens, is(2));
		assertThat(timers.set.size(), is(2));
	}

	@Example
	public void failsAnAssociationThatCouldNotStartInsteadOfThrowing() {
		final ScheduledThreadPoolExecutor stopped = new ScheduledThreadPoolExecutor(1);
		final List<ChainTunnel> broken = List.of(
			new ChainTunnel(() -> {
				throw new IllegalStateException("The spork of " + REMOTE + " cannot be read");
			}, session, timers),
			new ChainTunnel(() -> Optional.of(ProxySettings.DEFAULTS), (connect, country, listener) -> {
				throw new IllegalStateException("No session to " + REMOTE);
			}, timers),
			new ChainTunnel(() -> Optional.of(ProxySettings.DEFAULTS), session, stopped));
		final List<List<String>> heardPerAssociation = new ArrayList<>();

		stopped.shutdown();

		final List<ILoggingEvent> warnings = warningsWhile(() -> broken.forEach(each -> {
			final Heard failed = new Heard();

			each.associate(Optional.empty(), failed);
			heardPerAssociation.add(failed.events);
		}));

		assertThat(heardPerAssociation, equalTo(List.of(List.of("failed GENERAL_FAILURE"),
			List.of("failed GENERAL_FAILURE"), List.of("failed GENERAL_FAILURE"))));
		assertThat(session.opens, is(0));
		assertThat(timers.only().isCancelled(), is(true));
		assertThat(warnings.size(), is(3));
		loggedByClassAlone(warnings.subList(0, 2), REMOTE.host());
	}

	@Example
	public void tellsAFailureTheSessionGaveBeforeAssociateReturned() {
		session.opening = listener -> listener.received(new Close(CloseReason.UNREACHABLE));

		final AssociationStream association = tunnel.associate(Optional.empty(), heard);

		assertThat(heard.events, equalTo(List.of("failed NETWORK_UNREACHABLE")));
		prodAfterTheEnd(association);
		assertThat(heard.events, equalTo(List.of("failed NETWORK_UNREACHABLE")));
		assertThat(session.closes, empty());
		assertThat(session.sent, empty());
	}

	/* An opener that told the session's outcome and then threw has no handle to give: the outcome stands, told
	   once, and nothing can be sent */
	@Example
	public void keepsWhatTheSessionToldBeforeItsOpenerThrew() {
		final List<SealedMessage> outcomes = List.of(new Close(CloseReason.UNREACHABLE), CONNECTED);
		final List<List<String>> heardPerAssociation = new ArrayList<>();

		for (SealedMessage outcome : outcomes) {
			final Heard listener = new Heard();
			final AssociationStream association = new ChainTunnel(() -> Optional.of(ProxySettings.DEFAULTS),
				(connect, country, told) -> {
					told.received(outcome);
					throw new IllegalStateException("No session");
				}, timers).associate(Optional.empty(), listener);

			assertThat(association.send(REMOTE, new byte[] { 1 }), is(false));
			heardPerAssociation.add(listener.events);
		}

		assertThat(heardPerAssociation, equalTo(List.of(List.of("failed NETWORK_UNREACHABLE"), List.of(OPENED))));
	}
}
