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

package org.unigrid.hedgehog.service.chain;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.Close;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.Connected;
import org.unigrid.hedgehog.proxy.session.Data;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import org.unigrid.hedgehog.proxy.socks.TunnelFailure;
import org.unigrid.hedgehog.proxy.socks.TunnelListener;
import org.unigrid.hedgehog.proxy.socks.TunnelStream;

public class ChainTunnelTest {
	private static final SocksAddress DESTINATION = new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 443);
	private static final SocksAddress BOUND = new SocksAddress(Socks5AddressType.IPv4, "192.0.2.10", 40_000);
	private static final Connected CONNECTED = new Connected(Socks5CommandStatus.SUCCESS, BOUND);
	private static final String HEARD_CONNECTED = "connected " + BOUND;
	private static final int CHUNK = 10;
	private static final int WAIT_SECONDS = 10;

	/* The session the tunnel opened, as the chain would see it. It takes whatever it is sent until a test says
	   otherwise, so that only the stream decides what reaches it, but fails a stream that never stops sending. */
	private static final class FakeSession implements SessionHandle, SessionOpener {
		private static final int MAX_SENT = 1000;

		private final List<SealedMessage> sent = new CopyOnWriteArrayList<>();
		private final List<CloseReason> closes = new CopyOnWriteArrayList<>();
		private Consumer<SessionListener> opening = listener -> { };
		private RuntimeException refusal;
		private RuntimeException closeFailure;
		private volatile Connect connect;
		private volatile SessionListener listener;
		private volatile boolean writable = true;
		private int piece = CHUNK;
		private int opens;

		@Override
		public SessionHandle open(final Connect connect, final SessionListener listener) {
			this.connect = connect;
			this.listener = listener;
			opens++;
			opening.accept(listener);
			return this;
		}

		@Override
		public void send(final SealedMessage message) {
			if (sent.size() >= MAX_SENT) {
				throw new AssertionError("The stream does not stop sending");
			}

			if (refusal != null) {
				throw refusal;
			}

			sent.add(message);
		}

		@Override
		public boolean isWritable() {
			return writable;
		}

		@Override
		public int maxDataBytes() {
			return piece;
		}

		@Override
		public void close(final CloseReason reason) {
			closes.add(reason);

			if (closeFailure != null) {
				throw closeFailure;
			}
		}
	}

	/* What the front end hears, and whether it heard anything while the lock of its stream was held */
	private final class Heard implements TunnelListener {
		private final List<String> events = new CopyOnWriteArrayList<>();
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private Consumer<String> reaction = event -> { };
		private volatile boolean underLock;

		private void heard(final String event) {
			final SessionListener stream = session.listener;

			underLock |= stream != null && Thread.holdsLock(stream);
			events.add(event);
			reaction.accept(event);
		}

		@Override
		public void connected(final SocksAddress bound) {
			heard("connected " + bound);
		}

		@Override
		public void failed(final TunnelFailure failure) {
			heard("failed " + failure);
		}

		@Override
		public void received(final byte[] received) {
			bytes.writeBytes(received);
			heard("received");
		}

		@Override
		public void writabilityChanged(final boolean writable) {
			heard("writable " + writable);
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

	/* The ways a stream ends, with what the front end and the session heard of it */
	private enum Ending {
		REFUSED(List.of("failed HOST_UNREACHABLE"), List.of(CloseReason.DONE)),
		UNANSWERED(List.of("failed NETWORK_UNREACHABLE"), List.of()),
		EXPIRED(List.of("failed TTL_EXPIRED"), List.of(CloseReason.DONE)),
		ABANDONED(List.of(), List.of(CloseReason.DONE)),
		FINISHED(List.of(HEARD_CONNECTED, "closed"), List.of()),
		DROPPED(List.of(HEARD_CONNECTED, "closed"), List.of(CloseReason.BROKEN)),
		CLOSED(List.of(HEARD_CONNECTED), List.of(CloseReason.DONE));

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
	void heardNothingUnderTheLockOfItsStream() {
		assertThat(heard.underLock, is(false));
	}

	private TunnelStream opened() {
		final TunnelStream stream = tunnel.open(DESTINATION, heard);

		session.listener.received(CONNECTED);
		return stream;
	}

	private TunnelStream holding() {
		final TunnelStream stream = opened();

		stream.setReadable(false);
		return stream;
	}

	private TunnelStream ended(final Ending ending) {
		final TunnelStream stream = ending.heard.contains(HEARD_CONNECTED) ? opened()
			: tunnel.open(DESTINATION, heard);

		switch (ending) {
			case REFUSED -> session.listener.received(new Connected(Socks5CommandStatus.HOST_UNREACHABLE,
				SocksAddress.UNSPECIFIED));
			case UNANSWERED -> session.listener.received(new Close(CloseReason.UNREACHABLE));
			case EXPIRED -> timers.advance(ChainTunnel.DEADLINE_MILLIS);
			case FINISHED -> session.listener.received(new Close(CloseReason.DONE));
			case DROPPED -> {
				stream.setReadable(false);
				session.listener.received(data(ChainTunnel.MAX_HELD_BYTES + 1));
			}
			case CLOSED -> {
				stream.setReadable(false);
				session.listener.received(data(3));
				stream.close();
			}
			case ABANDONED -> stream.close();
		}

		return stream;
	}

	/* Everything the session and the front end can still do to a stream */
	private void prodAfterTheEnd(final TunnelStream stream) {
		session.listener.received(CONNECTED);
		session.listener.received(new Connected(Socks5CommandStatus.FAILURE, SocksAddress.UNSPECIFIED));
		session.listener.received(data(3));
		session.listener.writabilityChanged(true);
		session.listener.received(new Close(CloseReason.IDLE));
		stream.setReadable(false);
		stream.setReadable(true);
		stream.write(new byte[CHUNK]);
		stream.close();
		timers.advance(ChainTunnel.DEADLINE_MILLIS);
	}

	private static Data data(final int length) {
		return new Data(new byte[length]);
	}

	private static List<Integer> sentLengths(final FakeSession session) {
		return session.sent.stream().map(message -> ((Data) message).bytes().length).toList();
	}

	private static void meet(final CyclicBarrier barrier) {
		try {
			barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Example
	public void pinsWhatAStreamHoldsAndHowLongItWaitsForItsReply() {
		assertThat(ChainTunnel.MAX_HELD_BYTES, is(1024 * 1024));
		assertThat(ChainTunnel.DEADLINE_MILLIS, is(120_000L));
	}

	@Example
	public void opensASessionThatConnectsToTheDestination() {
		tunnel.open(DESTINATION, heard);

		assertThat(session.connect, equalTo(new Connect(Socks5CommandType.CONNECT, DESTINATION)));
		assertThat(heard.events, equalTo(List.of()));

		session.listener.received(CONNECTED);
		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED)));
	}

	@Property(tries = 8)
	public void failsWithTheReplyTheExitGaveAndIgnoresTheCloseThatFollows(@ForAll @IntRange(min = 1, max = 8) int code) {
		final Socks5CommandStatus status = Socks5CommandStatus.valueOf((byte) code);

		tunnel.open(DESTINATION, heard);
		session.listener.received(new Connected(status, SocksAddress.UNSPECIFIED));
		session.listener.received(new Close(CloseReason.REFUSED));

		assertThat(heard.events, equalTo(List.of("failed " + TunnelFailure.of(status))));
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	/* An exit that answers with a failure, sends no Close and keeps sending would keep the session alive for as long
	   as it likes, as the front end hangs up on a stream that has ended already */
	@Example
	public void closesTheSessionOfAStreamTheExitRefusedThoughTheExitDoesNot() {
		final TunnelStream stream = tunnel.open(DESTINATION, heard);

		session.listener.received(new Connected(Socks5CommandStatus.HOST_UNREACHABLE, SocksAddress.UNSPECIFIED));
		stream.close();
		session.listener.received(data(3));
		session.listener.received(new Connected(Socks5CommandStatus.FAILURE, SocksAddress.UNSPECIFIED));

		assertThat(heard.events, equalTo(List.of("failed HOST_UNREACHABLE")));
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	@Example
	public void closesTheSessionOfAStreamRefusedBeforeItsHandleCameBack() {
		session.opening = listener -> listener.received(new Connected(Socks5CommandStatus.CONNECTION_REFUSED,
			SocksAddress.UNSPECIFIED));
		tunnel.open(DESTINATION, heard);

		assertThat(heard.events, equalTo(List.of("failed CONNECTION_REFUSED")));
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	@Property(tries = 10)
	public void failsWithTheReasonOfASessionThatClosedBeforeItsReply(@ForAll CloseReason reason) {
		tunnel.open(DESTINATION, heard);
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

		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED, "closed")));
	}

	@Example
	public void tellsNothingOfTheChainBeforeTheReply() {
		tunnel.open(DESTINATION, heard);
		session.listener.writabilityChanged(false);
		session.listener.received(new Data(new byte[] { 1 }));
		session.listener.received(CONNECTED);
		session.listener.received(new Data(new byte[] { 2 }));

		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED, "received")));
		assertThat(heard.bytes.toByteArray(), equalTo(new byte[] { 2 }));
	}

	@Provide
	public Arbitrary<List<byte[]>> chunks() {
		return Arbitraries.bytes().array(byte[].class).ofMinSize(1).ofMaxSize(50).list().ofMaxSize(6);
	}

	@Property(tries = 30)
	public void relaysWhatTheDestinationSendsInOrder(@ForAll("chunks") List<byte[]> chunks) {
		final ByteArrayOutputStream expected = new ByteArrayOutputStream();

		opened();
		chunks.forEach(chunk -> {
			session.listener.received(new Data(chunk));
			expected.writeBytes(chunk);
		});

		assertThat(heard.bytes.toByteArray(), equalTo(expected.toByteArray()));
	}

	@Example
	public void tellsTheListenerOnceWhenTheDestinationCloses() {
		opened();
		session.listener.received(new Close(CloseReason.DONE));
		session.listener.received(new Close(CloseReason.DONE));
		session.listener.received(data(3));

		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED, "closed")));
	}

	@Example
	public void holdsBackWhatTheDestinationSendsWhileTheClientIsSlow() {
		final TunnelStream stream = holding();

		session.listener.received(new Data(new byte[] { 1, 2 }));
		session.listener.received(new Data(new byte[] { 3 }));
		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED)));

		stream.setReadable(true);
		session.listener.received(new Data(new byte[] { 4 }));

		assertThat(heard.bytes.toByteArray(), equalTo(new byte[] { 1, 2, 3, 4 }));
		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED, "received", "received", "received")));
	}

	@Example
	public void deliversWhatItHeldBeforeTellingThatTheDestinationClosed() {
		final TunnelStream stream = holding();

		session.listener.received(new Data(new byte[] { 1, 2 }));
		session.listener.received(new Data(new byte[] { 3 }));
		session.listener.received(new Close(CloseReason.DONE));
		stream.setReadable(true);

		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED, "received", "received", "closed")));
		assertThat(heard.bytes.toByteArray(), equalTo(new byte[] { 1, 2, 3 }));
	}

	@Provide
	public Arbitrary<List<Object>> interleavings() {
		return Arbitraries.oneOf(Arbitraries.bytes().array(byte[].class).ofMinSize(1).ofMaxSize(20).asGeneric(),
			Arbitraries.of(true, false).asGeneric()).list().ofMaxSize(30);
	}

	/* Bytes or a call of setReadable, in any order */
	@Property(tries = 200)
	public void deliversEveryByteOnceAndInOrderThenTheEndWhateverTheClientReads(
		@ForAll("interleavings") List<Object> steps) {

		final TunnelStream stream = opened();
		final ByteArrayOutputStream sent = new ByteArrayOutputStream();
		boolean readable = true;
		int delivered = 0;

		for (Object step : steps) {
			if (step instanceof byte[] bytes) {
				sent.writeBytes(bytes);
				session.listener.received(new Data(bytes));
			} else {
				readable = (Boolean) step;
				stream.setReadable(readable);
			}

			delivered = readable ? sent.size() : delivered;
			assertThat(heard.bytes.toByteArray(), equalTo(Arrays.copyOf(sent.toByteArray(), delivered)));
		}

		session.listener.received(new Close(CloseReason.DONE));

		assertThat(heard.bytes.toByteArray(), equalTo(sent.toByteArray()));
		assertThat(heard.events.get(heard.events.size() - 1), equalTo("closed"));
		assertThat(heard.events.stream().filter("closed"::equals).count(), is(1L));
	}

	/* Whatever the client held back by the byte, one byte more than the stream holds drops it */
	@Property(tries = 3)
	public void dropsAStreamOnlyWhenItWouldHoldMoreThanItMay(
		@ForAll @IntRange(min = ChainTunnel.MAX_HELD_BYTES - 1, max = ChainTunnel.MAX_HELD_BYTES + 1) int held) {

		final TunnelStream stream = holding();

		session.listener.received(data(held - 1));
		session.listener.received(data(1));
		stream.setReadable(true);

		if (held > ChainTunnel.MAX_HELD_BYTES) {
			assertThat(session.closes, equalTo(List.of(CloseReason.BROKEN)));
			assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED, "closed")));
		} else {
			assertThat(session.closes, empty());
			assertThat(heard.bytes.size(), is(held));
		}
	}

	@Example
	public void dropsWhatItHeldButNotWhatItToldWhenTheClientFellTooFarBehind() {
		final TunnelStream stream = opened();

		session.listener.received(data(3));
		stream.setReadable(false);
		session.listener.received(data(ChainTunnel.MAX_HELD_BYTES));
		session.listener.received(data(1));

		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED, "received", "closed")));
		assertThat(heard.bytes.size(), is(3));
		assertThat(session.closes, equalTo(List.of(CloseReason.BROKEN)));
	}

	/* A message without bytes costs the bound nothing, so it is neither held nor told */
	@Example
	public void keepsNoEmptyMessageWhileTheClientDoesNotRead() {
		final TunnelStream stream = holding();

		for (int count = 0; count < 3; count++) {
			session.listener.received(data(0));
		}

		stream.setReadable(true);
		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED)));
	}

	/* What a client that reads in turns held back before is not counted against it again */
	@Example
	public void countsWhatIsHeldFromZeroAfterEveryRelease() {
		final TunnelStream stream = opened();

		for (int cycle = 0; cycle < 3; cycle++) {
			stream.setReadable(false);
			session.listener.received(data(ChainTunnel.MAX_HELD_BYTES / 2 + 1));
			stream.setReadable(true);
		}

		assertThat(session.closes, empty());
		assertThat(heard.bytes.size(), is(3 * (ChainTunnel.MAX_HELD_BYTES / 2 + 1)));
	}

	@Example
	public void holdsTheBoundPerStreamAndNotPerTunnel() {
		holding();

		final SessionListener ofFirst = session.listener;
		final Heard other = new Heard();
		final TunnelStream second = tunnel.open(DESTINATION, other);
		final SessionListener ofSecond = session.listener;

		ofSecond.received(CONNECTED);
		second.setReadable(false);
		ofFirst.received(data(ChainTunnel.MAX_HELD_BYTES * 3 / 5));
		ofSecond.received(data(ChainTunnel.MAX_HELD_BYTES * 3 / 5));

		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED)));
		assertThat(other.events, equalTo(List.of(HEARD_CONNECTED)));
		assertThat(session.closes, empty());
	}

	/* A deadline that had started when the stream ended runs its command all the same */
	@Example
	public void tellsNothingWhenADeadlineThatHadStartedRunsAfterTheStreamWasClosed() {
		final TunnelStream stream = tunnel.open(DESTINATION, heard);
		final Runnable command = timers.only().command;

		stream.close();
		command.run();

		assertThat(heard.events, empty());
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	@Example
	public void tellsNothingMoreWhenADeadlineThatHadStartedRunsAfterTheSessionFailed() {
		tunnel.open(DESTINATION, heard);

		final Runnable command = timers.only().command;

		session.listener.received(new Close(CloseReason.UNREACHABLE));
		command.run();

		assertThat(heard.events, equalTo(List.of("failed NETWORK_UNREACHABLE")));
		assertThat(session.closes, empty());
	}

	@Example
	public void endsAStreamWhoseSessionCannotBeClosed() {
		final TunnelStream stream = opened();

		session.closeFailure = new IllegalStateException("The session cannot be closed");
		stream.close();

		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED)));
	}

	@Property(tries = 7)
	public void endsOnceAndTellsNothingMore(@ForAll Ending ending) {
		final TunnelStream stream = ended(ending);

		assertThat(heard.events, equalTo(ending.heard));
		assertThat(session.closes, equalTo(ending.closes));
		assertThat(timers.only().isDone(), is(true));

		prodAfterTheEnd(stream);

		assertThat(heard.events, equalTo(ending.heard));
		assertThat(session.closes, equalTo(ending.closes));
		assertThat(session.sent, empty());
	}

	@Property(tries = 30)
	public void splitsWhatItWritesIntoMessagesOfOneEntry(@ForAll @Size(max = 300) byte[] bytes) {
		final ByteArrayOutputStream joined = new ByteArrayOutputStream();

		opened().write(bytes);
		session.sent.forEach(message -> {
			assertThat(((Data) message).bytes().length <= CHUNK, is(true));
			joined.writeBytes(((Data) message).bytes());
		});

		assertThat(joined.toByteArray(), equalTo(bytes));
	}

	@Example
	public void cutsWhatItWritesAtTheSizeOfOneEntry() {
		final TunnelStream stream = opened();
		final List<List<Integer>> pieces = new ArrayList<>();

		for (int length : List.of(0, CHUNK - 1, CHUNK, CHUNK + 1, 2 * CHUNK, 2 * CHUNK + 1)) {
			session.sent.clear();
			stream.write(new byte[length]);
			pieces.add(sentLengths(session));
		}

		assertThat(pieces, equalTo(List.of(List.of(), List.of(CHUNK - 1), List.of(CHUNK), List.of(CHUNK, 1),
			List.of(CHUNK, CHUNK), List.of(CHUNK, CHUNK, 1))));
	}

	@Example
	public void readsTheSizeOfAnEntryForEveryWrite() {
		final TunnelStream stream = opened();

		stream.write(new byte[CHUNK]);
		session.piece = CHUNK / 2;
		stream.write(new byte[CHUNK]);

		assertThat(sentLengths(session), equalTo(List.of(CHUNK, CHUNK / 2, CHUNK / 2)));
	}

	@Example
	public void writesNothingWhileTheSessionCarriesNoData() {
		final TunnelStream stream = opened();

		session.piece = 0;
		stream.write(new byte[CHUNK]);

		assertThat(session.sent, empty());
	}

	@Example
	public void dropsWhatItWritesIntoASessionThatHasEnded() {
		final TunnelStream stream = opened();

		session.refusal = new IllegalStateException("The session has ended");
		stream.write(new byte[CHUNK * 3]);

		assertThat(session.sent, empty());
	}

	@Example
	public void leavesAMessageThatDoesNotFitToTheFrontEnd() {
		final TunnelStream stream = opened();
		final IllegalArgumentException tooLarge = new IllegalArgumentException("Too large");

		session.refusal = tooLarge;

		try {
			stream.write(new byte[CHUNK]);
			throw new AssertionError("The write went through");
		} catch (IllegalArgumentException ex) {
			assertThat(ex, is(tooLarge));
		}
	}

	@Example
	public void isWritableWhileTheChainTakesEntriesAndPassesOnWhenItDoesAgain() {
		final TunnelStream stream = opened();

		session.writable = false;
		assertThat(stream.isWritable(), is(false));
		session.listener.writabilityChanged(false);
		session.writable = true;
		session.listener.writabilityChanged(true);

		assertThat(stream.isWritable(), is(true));
		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED, "writable false", "writable true")));
	}

	@Example
	public void isNotWritableWithoutASession() {
		final TunnelStream refused = new ChainTunnel(Optional::empty, session, timers).open(DESTINATION, heard);

		assertThat(refused.isWritable(), is(false));
	}

	@Example
	public void closesTheSessionWhenTheClientDoes() {
		final TunnelStream stream = opened();

		stream.close();
		stream.close();
		session.listener.received(new Close(CloseReason.DONE));

		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED)));
	}

	@Example
	public void tellsNothingThatWaitedOnceTheClientClosed() {
		final TunnelStream stream = holding();

		heard.reaction = event -> stream.close();
		session.listener.received(new Data(new byte[] { 1 }));
		session.listener.received(new Data(new byte[] { 2 }));
		heard.events.clear();
		stream.setReadable(true);

		assertThat(heard.events, equalTo(List.of("received")));
		assertThat(heard.bytes.toByteArray(), equalTo(new byte[] { 1 }));
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	@Example
	public void letsTheListenerCallBackIntoItsStream() {
		final TunnelStream stream = opened();

		heard.reaction = event -> {
			if ("received".equals(event)) {
				stream.setReadable(false);
			} else if ("closed".equals(event)) {
				stream.close();
			}
		};

		session.listener.received(new Data(new byte[] { 1 }));
		session.listener.received(new Data(new byte[] { 2 }));
		assertThat(heard.bytes.toByteArray(), equalTo(new byte[] { 1 }));

		stream.setReadable(true);
		session.listener.received(new Close(CloseReason.DONE));

		assertThat(heard.bytes.toByteArray(), equalTo(new byte[] { 1, 2 }));
		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED, "received", "received", "closed")));
		assertThat(session.closes, empty());
	}

	@Example
	public void goesOnWhenTheListenerThrows() {
		opened();
		heard.reaction = event -> {
			throw new IllegalStateException("The front end failed");
		};

		session.listener.received(new Data(new byte[] { 1 }));
		session.listener.received(new Data(new byte[] { 2 }));
		session.listener.received(new Close(CloseReason.DONE));

		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED, "received", "received", "closed")));
	}

	@Example
	public void expiresAStreamThatNoExitAnswersInTime() {
		tunnel.open(DESTINATION, heard);
		timers.advance(ChainTunnel.DEADLINE_MILLIS - 1);
		assertThat(heard.events, empty());
		assertThat(session.closes, empty());

		timers.advance(1);
		assertThat(heard.events, equalTo(List.of("failed TTL_EXPIRED")));
		assertThat(session.closes, equalTo(List.of(CloseReason.DONE)));
	}

	@Example
	public void leavesAStreamThatGotItsReplyToItsSession() {
		opened();
		assertThat(timers.only().isCancelled(), is(true));

		timers.only().command.run();
		timers.advance(ChainTunnel.DEADLINE_MILLIS + 1);

		assertThat(heard.events, equalTo(List.of(HEARD_CONNECTED)));
		assertThat(session.closes, empty());
	}

	@Example
	public void closesTheSessionOfAStreamThatExpiredBeforeItsHandleCameBack() {
		session.opening = listener -> timers.advance(ChainTunnel.DEADLINE_MILLIS);
		tunnel.open(DESTINATION, heard);

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

		tunnel.open(DESTINATION, heard);
		exit.start();
		meet(start);
		timers.only().command.run();
		exit.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

		assertThat(heard.events, either(equalTo(List.of(HEARD_CONNECTED)))
			.or(equalTo(List.of("failed TTL_EXPIRED"))));
		assertThat(session.closes, equalTo(heard.events.get(0).equals(HEARD_CONNECTED) ? List.<CloseReason>of()
			: List.of(CloseReason.DONE)));
	}

	/* Both wait for the lock of the stream, so whichever takes it first decides and the other finds it decided */
	@Example
	public void decidesBetweenTheReplyAndTheDeadlineUnderTheLockOfTheStream() throws InterruptedException {
		tunnel.open(DESTINATION, heard);

		final List<Thread> racing = List.of(new Thread(() -> session.listener.received(CONNECTED)),
			new Thread(timers.only().command));

		synchronized (session.listener) {
			racing.forEach(Thread::start);
			await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> racing.stream()
				.allMatch(thread -> thread.getState() == Thread.State.BLOCKED));
			assertThat(heard.events, empty());
		}

		for (Thread thread : racing) {
			thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
		}

		assertThat(heard.events, either(equalTo(List.of(HEARD_CONNECTED)))
			.or(equalTo(List.of("failed TTL_EXPIRED"))));
	}

	@Property(tries = 20)
	public void deliversEveryByteOnceAndInOrderWhileTheClientPausesOnItsOwnThread(@ForAll Random random)
		throws InterruptedException {

		final TunnelStream stream = opened();
		final ByteArrayOutputStream sent = new ByteArrayOutputStream();
		final CountDownLatch ended = new CountDownLatch(1);
		final Thread exit = new Thread(() -> {
			for (int i = 0; i < 500; i++) {
				final byte[] bytes = new byte[1 + i % 20];

				Arrays.fill(bytes, (byte) i);
				sent.writeBytes(bytes);
				session.listener.received(new Data(bytes));
			}

			session.listener.received(new Close(CloseReason.DONE));
		});

		heard.reaction = event -> {
			if ("closed".equals(event)) {
				ended.countDown();
			}
		};

		exit.start();

		for (int i = 0; ended.getCount() > 0 && i < 1_000_000; i++) {
			stream.setReadable(random.nextBoolean());
		}

		assertThat(ended.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
		exit.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
		assertThat(heard.bytes.toByteArray(), equalTo(sent.toByteArray()));
		assertThat(heard.events.get(heard.events.size() - 1), equalTo("closed"));
		assertThat(heard.events.stream().filter("closed"::equals).count(), is(1L));
	}

	@Example
	public void refusesEveryStreamWhileTheProxyIsOffOrDoesNotAllowConnect() {
		final List<Optional<ProxySettings>> refusing = List.of(Optional.empty(),
			Optional.of(new ProxySettings(8, 3600, 4, 16, 32L * 1024 * 1024, 30, 16 * 1024, 300,
				ProxySettings.UDP)));

		for (final Optional<ProxySettings> current : refusing) {
			final Heard refused = new Heard();

			new ChainTunnel(() -> current, session, timers).open(DESTINATION, refused);
			assertThat(refused.events, equalTo(List.of("failed NOT_ALLOWED")));
		}

		assertThat(session.listener == null, is(true));
		assertThat(timers.set, empty());
	}

	@Example
	public void readsTheSporkForEveryStream() {
		final AtomicReference<Optional<ProxySettings>> spork = new AtomicReference<>(Optional.empty());
		final ChainTunnel following = new ChainTunnel(spork::get, session, timers);
		final List<List<String>> heardPerStream = new ArrayList<>();

		for (Optional<ProxySettings> current : List.of(Optional.<ProxySettings>empty(),
			Optional.of(ProxySettings.DEFAULTS), Optional.<ProxySettings>empty())) {

			final Heard listener = new Heard();

			spork.set(current);
			following.open(DESTINATION, listener);
			heardPerStream.add(listener.events);
		}

		assertThat(heardPerStream, equalTo(List.of(List.of("failed NOT_ALLOWED"), List.of(),
			List.of("failed NOT_ALLOWED"))));
		assertThat(session.opens, is(1));
	}

	@Example
	public void failsAStreamThatCouldNotStartInsteadOfThrowing() {
		final ScheduledThreadPoolExecutor stopped = new ScheduledThreadPoolExecutor(1);
		final List<ChainTunnel> broken = List.of(
			new ChainTunnel(() -> {
				throw new IllegalStateException("The spork cannot be read");
			}, session, timers),
			new ChainTunnel(() -> Optional.of(ProxySettings.DEFAULTS), (connect, listener) -> {
				throw new IllegalStateException("No session");
			}, timers),
			new ChainTunnel(() -> Optional.of(ProxySettings.DEFAULTS), session, stopped));

		stopped.shutdown();

		for (ChainTunnel each : broken) {
			final Heard failed = new Heard();

			each.open(DESTINATION, failed);
			assertThat(failed.events, equalTo(List.of("failed GENERAL_FAILURE")));
		}

		assertThat(session.opens, is(0));
		assertThat(timers.only().isCancelled(), is(true));
	}

	@Example
	public void tellsAFailureTheSessionGaveBeforeOpenReturned() {
		session.opening = listener -> listener.received(new Close(CloseReason.UNREACHABLE));

		final TunnelStream stream = tunnel.open(DESTINATION, heard);

		assertThat(heard.events, equalTo(List.of("failed NETWORK_UNREACHABLE")));
		prodAfterTheEnd(stream);
		assertThat(heard.events, equalTo(List.of("failed NETWORK_UNREACHABLE")));
		assertThat(session.closes, empty());
		assertThat(session.sent, empty());
	}

	@Example
	public void tellsARefusalOnceEvenWhenTheListenerThrows() {
		heard.reaction = event -> {
			throw new IllegalStateException("The front end failed");
		};

		new ChainTunnel(Optional::empty, session, timers).open(DESTINATION, heard);
		tunnel.open(DESTINATION, heard);
		session.listener.received(new Close(CloseReason.UNREACHABLE));

		assertThat(heard.events, equalTo(List.of("failed NOT_ALLOWED", "failed NETWORK_UNREACHABLE")));
	}
}
