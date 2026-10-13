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

package org.unigrid.hedgehog.proxy.exit;

import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterProperty;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/* Real connections, all of them to 127.0.0.0/8 */
public class NettyExitConnectorTest {
	private static final byte[] HELLO = "hello".getBytes(StandardCharsets.US_ASCII);
	private static final long QUIET_MILLIS = 200;
	private static final int SHORT_TIMEOUT_MILLIS = 300;

	/* Longer than the second after which an unanswered SYN is sent again */
	private static final int RESENT_SYN_MILLIS = 2000;

	/* A cancel that races the hand-over moves from before a loopback dial to well after it in these steps */
	private static final int RACES = 300;
	private static final int RACE_STEPS = 100;
	private static final int RACE_STEP_SPINS = 400;

	/* More than the kernel buffers of both ends of a loopback connection hold */
	private static final int UNREAD_BYTES = 32 * 1024 * 1024;

	private static final int CLOSE_GRACE_MILLIS = 600;
	private static final int UPLOAD_BYTES = 16 * 1024 * 1024;
	private static final int ECHOED_BYTES = 512 * 1024;
	private static final int CHUNK_BYTES = 16 * 1024;
	private static final int CHUNKS = 64;

	private final NioEventLoopGroup group = new NioEventLoopGroup(1);
	private final ExitConnector connector = new NettyExitConnector(group, 2000);
	private final ExitConnector brief = new NettyExitConnector(group, 2000, CLOSE_GRACE_MILLIS);

	@AfterProperty
	public void stopEventLoop() {
		group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
	}

	private static int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, EchoServer.LOOPBACK)) {
			return socket.getLocalPort();
		}
	}

	@Example
	public void shouldReachTheDestinationAndRelayBothWays() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final RecordingTargetListener listener = new RecordingTargetListener();

			connector.connect(List.of(EchoServer.LOOPBACK), echo.port(), listener);

			final Target target = listener.target();

			assertThat(target.localAddress().getAddress(), equalTo(EchoServer.LOOPBACK));
			target.setReadable(true);
			target.write(HELLO);
			assertThat(listener.received(HELLO.length), equalTo(HELLO));
			target.close();
			assertThat(listener.closed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
				is(true));
		}
	}

	@Example
	public void shouldReachTheFirstAddressThatAcceptsAfterRefusedOnes() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final RecordingTargetListener listener = new RecordingTargetListener();

			connector.connect(List.of(EchoServer.loopback(2), EchoServer.LOOPBACK), echo.port(), listener);
			listener.target().setReadable(true);
			listener.target().write(HELLO);
			assertThat(listener.received(HELLO.length), equalTo(HELLO));
		}
	}

	@Example
	public void shouldDialNothingMoreOnceOneAddressAccepted() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final RecordingTargetListener listener = new RecordingTargetListener();

			connector.connect(List.of(EchoServer.LOOPBACK, EchoServer.loopback(2)), echo.port(), listener);
			listener.target();
			Expect.assertThrows(TimeoutException.class, () -> listener.failed.get(QUIET_MILLIS,
				TimeUnit.MILLISECONDS));
			assertThat(listener.events(), equalTo(List.of("connected")));
		}
	}

	@Example
	public void shouldCallTheListenerOnceAndInOrder() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final RecordingTargetListener listener = new RecordingTargetListener();

			connector.connect(List.of(EchoServer.LOOPBACK), echo.port(), listener);
			listener.target().setReadable(true);
			listener.target().write(HELLO);
			listener.received(HELLO.length);
			listener.target().close();
			listener.closed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			Thread.sleep(QUIET_MILLIS);
			assertThat(listener.events(), equalTo(List.of("connected", "received", "closed")));
		}
	}

	@Example
	public void shouldCallTheListenerOnceWhenNoAddressAccepts() throws Exception {
		final RecordingTargetListener listener = new RecordingTargetListener();

		connector.connect(List.of(EchoServer.LOOPBACK, EchoServer.loopback(2)), closedPort(), listener);
		listener.failed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
		Thread.sleep(QUIET_MILLIS);
		assertThat(listener.events(), equalTo(List.of("failed " + Socks5CommandStatus.CONNECTION_REFUSED)));
	}

	@Example
	public void shouldRelayManyChunksInOrder() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final RecordingTargetListener listener = new RecordingTargetListener();
			final ByteArrayOutputStream sent = new ByteArrayOutputStream();

			connector.connect(List.of(EchoServer.LOOPBACK), echo.port(), listener);
			listener.target().setReadable(true);

			for (int chunk = 0; chunk < CHUNKS; chunk++) {
				final byte[] bytes = new byte[CHUNK_BYTES];

				Arrays.fill(bytes, (byte) chunk);
				listener.target().write(bytes);
				sent.writeBytes(bytes);
			}

			assertThat(listener.received(sent.size()), equalTo(sent.toByteArray()));
		}
	}

	@Example
	public void shouldNotReadTheListItWasGivenAfterTheCallReturns() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final RecordingTargetListener listener = new RecordingTargetListener();
			final List<InetAddress> addresses = new ArrayList<>(List.of(EchoServer.loopback(2),
				EchoServer.LOOPBACK));

			connector.connect(addresses, echo.port(), listener);
			addresses.clear();
			assertThat(listener.target().localAddress().getAddress(), equalTo(EchoServer.LOOPBACK));
		}
	}

	/* Refused addresses, then the one that listens: nothing listens on 127.0.0.10 and up at the echo's port */
	private static List<InetAddress> refusedBefore(int refused, InetAddress listening) {
		final List<InetAddress> addresses = new ArrayList<>(IntStream.range(0, refused)
			.mapToObj(i -> EchoServer.loopback(10 + i)).toList());

		addresses.add(listening);
		return addresses;
	}

	@Example
	public void shouldTryNoMoreAddressesThanTheLimit() throws Exception {
		final InetAddress listening = EchoServer.loopback(200);

		try (EchoServer echo = new EchoServer(listening)) {
			final RecordingTargetListener within = new RecordingTargetListener();
			final RecordingTargetListener beyond = new RecordingTargetListener();

			connector.connect(refusedBefore(NettyExitConnector.MAX_ADDRESSES - 1, listening), echo.port(),
				within);
			connector.connect(refusedBefore(NettyExitConnector.MAX_ADDRESSES, listening), echo.port(), beyond);

			assertThat(NettyExitConnector.MAX_ADDRESSES, is(8));
			assertThat(within.target().localAddress().getPort() > 0, is(true));
			assertThat(beyond.failed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
				is(Socks5CommandStatus.CONNECTION_REFUSED));
		}
	}

	@Example
	public void shouldReadNothingUntilAskedTo() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final RecordingTargetListener listener = new RecordingTargetListener();

			connector.connect(List.of(EchoServer.LOOPBACK), echo.port(), listener);
			listener.target().write(HELLO);
			RecordingTargetListener.await(() -> echo.echoed() >= HELLO.length);
			Expect.assertThrows(TimeoutException.class, () -> listener.receivedAny.get(QUIET_MILLIS,
				TimeUnit.MILLISECONDS));
			assertThat(listener.received().length, is(0));
			listener.target().setReadable(true);
			assertThat(listener.received(HELLO.length), equalTo(HELLO));
		}
	}

	@Example
	public void shouldTellWhenTheDestinationCloses() throws Exception {
		final RecordingTargetListener listener = new RecordingTargetListener();

		try (EchoServer echo = new EchoServer()) {
			connector.connect(List.of(EchoServer.LOOPBACK), echo.port(), listener);
			listener.target().setReadable(true);
			listener.target().write(HELLO);
			listener.received(HELLO.length);
		}

		assertThat(listener.closed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), is(true));
	}

	@Example
	public void shouldReportARefusedConnection() throws Exception {
		final RecordingTargetListener listener = new RecordingTargetListener();

		connector.connect(List.of(EchoServer.LOOPBACK), closedPort(), listener);
		assertThat(listener.failed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
			is(Socks5CommandStatus.CONNECTION_REFUSED));
	}

	@Example
	public void shouldReportTheStatusOfTheLastAttempt() throws Exception {
		final ExitConnector impatient = new NettyExitConnector(group, SHORT_TIMEOUT_MILLIS);

		try (Blackhole hole = new Blackhole()) {
			final RecordingTargetListener timedOutLast = new RecordingTargetListener();
			final RecordingTargetListener refusedLast = new RecordingTargetListener();

			impatient.connect(List.of(EchoServer.loopback(10), EchoServer.LOOPBACK), hole.port(), timedOutLast);
			impatient.connect(List.of(EchoServer.LOOPBACK, EchoServer.loopback(10)), hole.port(), refusedLast);
			assertThat(timedOutLast.failed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
				is(Socks5CommandStatus.TTL_EXPIRED));
			assertThat(refusedLast.failed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
				is(Socks5CommandStatus.CONNECTION_REFUSED));
		}
	}

	@Example
	public void shouldCountTheBytesTheDestinationHasNotTakenYet() throws Exception {
		try (ServerSocket silent = new ServerSocket(0, 1, EchoServer.LOOPBACK)) {
			final RecordingTargetListener listener = new RecordingTargetListener();

			brief.connect(List.of(EchoServer.LOOPBACK), silent.getLocalPort(), listener);

			final Target target = listener.target();

			assertThat(target.backlogBytes(), is(0L));
			target.write(new byte[UNREAD_BYTES]);
			RecordingTargetListener.await(() -> target.backlogBytes() > 0);
			target.close();
			RecordingTargetListener.await(() -> target.backlogBytes() == 0);
		}
	}

	@Example
	public void shouldSeeTheBacklogFallAsTheDestinationTakesTheBytes() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final RecordingTargetListener listener = new RecordingTargetListener();

			connector.connect(List.of(EchoServer.LOOPBACK), echo.port(), listener);

			final Target target = listener.target();

			target.write(new byte[ECHOED_BYTES]);
			RecordingTargetListener.await(() -> echo.echoed() >= ECHOED_BYTES);
			RecordingTargetListener.await(() -> target.backlogBytes() == 0);
			assertThat(listener.closed.isDone(), is(false));
		}
	}

	@Example
	public void shouldDeliverWhatIsQueuedBeforeAClose() throws Exception {
		try (ServerSocket sink = new ServerSocket(0, 1, EchoServer.LOOPBACK)) {
			final RecordingTargetListener listener = new RecordingTargetListener();

			sink.setSoTimeout((int) RecordingTargetListener.TIMEOUT_MILLIS);
			connector.connect(List.of(EchoServer.LOOPBACK), sink.getLocalPort(), listener);

			try (Socket accepted = sink.accept()) {
				accepted.setSoTimeout((int) RecordingTargetListener.TIMEOUT_MILLIS);
				listener.target().write(new byte[UPLOAD_BYTES]);
				listener.target().close();
				assertThat(accepted.getInputStream().readAllBytes().length, is(UPLOAD_BYTES));
			}
		}
	}

	@Example
	public void shouldCloseOnceTheGraceIsOverWhenTheDestinationTakesNothing() throws Exception {
		try (ServerSocket silent = new ServerSocket(0, 1, EchoServer.LOOPBACK)) {
			final RecordingTargetListener listener = new RecordingTargetListener();

			brief.connect(List.of(EchoServer.LOOPBACK), silent.getLocalPort(), listener);

			final Target target = listener.target();

			target.write(new byte[UNREAD_BYTES]);
			RecordingTargetListener.await(() -> target.backlogBytes() > 0);
			target.close();
			Expect.assertThrows(TimeoutException.class, () -> listener.closed.get(CLOSE_GRACE_MILLIS / 3,
				TimeUnit.MILLISECONDS));
			assertThat(listener.closed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
				is(true));
		}
	}

	@Example
	public void shouldIgnoreWhatIsDoneToATargetThatHasClosed() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final RecordingTargetListener listener = new RecordingTargetListener();

			connector.connect(List.of(EchoServer.LOOPBACK), echo.port(), listener);
			listener.target().setReadable(true);
			listener.target().write(HELLO);
			listener.received(HELLO.length);
			listener.target().close();
			listener.closed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			listener.target().write(HELLO);
			listener.target().setReadable(true);
			listener.target().close();
			Thread.sleep(QUIET_MILLIS);
			assertThat(listener.events(), equalTo(List.of("connected", "received", "closed")));
		}
	}

	@Example
	public void shouldFailWithoutAnAddressToTry() throws Exception {
		final RecordingTargetListener listener = new RecordingTargetListener();

		connector.connect(List.of(), 80, listener);
		assertThat(listener.failed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
			is(Socks5CommandStatus.HOST_UNREACHABLE));
	}

	/* Returns once the loop ran what it was asked to do so far, and what that asked it to do next: a connect from
	   another thread registers the channel first and then dials */
	private static void flush(EventLoopGroup loop) throws Exception {
		for (int round = 0; round < 2; round++) {
			loop.next().submit(() -> { }).get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
		}
	}

	/* Holds the thread a while without giving the processor up, which moves where it meets the event loop */
	private static void spin(int times) {
		for (int spin = times; spin > 0; spin--) {
			Thread.onSpinWait();
		}
	}

	/* The cancel races the loop that hands the connection over, a little later each round: the dial has started, but
	   the connection may not have been made, or made and not yet handed over, or handed over. Whichever comes first
	   has it, and the other leaves it be. */
	@Example
	public void shouldHandAConnectionOverOrCloseItOnCancelButNeverBoth() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			for (int round = 0; round < RACES; round++) {
				final RecordingTargetListener listener = new RecordingTargetListener();
				final ExitConnector.Attempt attempt = connector.connect(List.of(EchoServer.LOOPBACK), echo.port(),
					listener);

				group.next().submit(() -> { }).get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
				spin(round % RACE_STEPS * RACE_STEP_SPINS);
				attempt.cancel();
				flush(group);

				if (listener.connected.isDone()) {
					assertThat(listener.events(), equalTo(List.of("connected")));
					listener.target().close();
				} else {
					assertThat(listener.events(), is(empty()));
				}
			}

			RecordingTargetListener.await(() -> echo.open() == 0);
		}
	}

	@Example
	public void shouldCloseTheConnectionBeingDialledWhenCancelled() throws Exception {
		try (Blackhole hole = new Blackhole()) {
			final RecordingTargetListener listener = new RecordingTargetListener();
			final ExitConnector.Attempt attempt = connector.connect(List.of(EchoServer.LOOPBACK), hole.port(),
				listener);

			flush(group);
			attempt.cancel();
			flush(group);
			assertThat(hole.takesAnotherWithin(RESENT_SYN_MILLIS), is(false));
			assertThat(listener.events(), is(empty()));
		}
	}

	@Example
	public void shouldTryNoFurtherAddressOnceCancelled() throws Exception {
		final ExitConnector impatient = new NettyExitConnector(group, SHORT_TIMEOUT_MILLIS);

		try (ServerSocket next = new ServerSocket(0, 1, EchoServer.LOOPBACK);
			Blackhole hole = new Blackhole(EchoServer.loopback(3), next.getLocalPort())) {
			final RecordingTargetListener listener = new RecordingTargetListener();
			final ExitConnector.Attempt attempt = impatient.connect(List.of(EchoServer.loopback(3),
				EchoServer.LOOPBACK), hole.port(), listener);

			flush(group);
			attempt.cancel();
			next.setSoTimeout(3 * SHORT_TIMEOUT_MILLIS);
			Expect.assertThrows(SocketTimeoutException.class, next::accept);
			assertThat(listener.events(), is(empty()));
		}
	}

	@Example
	public void shouldConnectNothingWhenCancelledAtOnce() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final RecordingTargetListener listener = new RecordingTargetListener();
			final ExitConnector.Attempt attempt = connector.connect(List.of(EchoServer.LOOPBACK), echo.port(),
				listener);

			attempt.cancel();
			attempt.cancel();
			flush(group);
			Expect.assertThrows(TimeoutException.class, () -> listener.connected.get(QUIET_MILLIS,
				TimeUnit.MILLISECONDS));
			assertThat(listener.events(), is(empty()));
			RecordingTargetListener.await(() -> echo.open() == 0);
		}
	}

	@Example
	public void shouldLeaveAConnectionTheListenerHasToItWhenCancelled() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final RecordingTargetListener listener = new RecordingTargetListener();
			final ExitConnector.Attempt attempt = connector.connect(List.of(EchoServer.LOOPBACK), echo.port(),
				listener);

			listener.target().setReadable(true);
			attempt.cancel();
			attempt.cancel();
			listener.target().write(HELLO);
			assertThat(listener.received(HELLO.length), equalTo(HELLO));
			assertThat(listener.events(), equalTo(List.of("connected", "received")));
		}
	}

	@Example
	public void shouldTellNothingMoreWhenCancelledAfterItFailed() throws Exception {
		final RecordingTargetListener listener = new RecordingTargetListener();
		final ExitConnector.Attempt attempt = connector.connect(List.of(EchoServer.LOOPBACK), closedPort(),
			listener);

		listener.failed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
		attempt.cancel();
		attempt.cancel();
		flush(group);
		assertThat(listener.events(), equalTo(List.of("failed " + Socks5CommandStatus.CONNECTION_REFUSED)));
	}

	/* What a stopping exit relies on, as it releases the loops and leaves its relays be */
	@Example
	public void shouldCloseTheConnectionBeingDialledAndTryNoMoreWhenTheGroupStops() throws Exception {
		final NioEventLoopGroup stopping = new NioEventLoopGroup(1);

		try (Blackhole hole = new Blackhole()) {
			final RecordingTargetListener listener = new RecordingTargetListener();

			new NettyExitConnector(stopping, 2000).connect(List.of(EchoServer.LOOPBACK, EchoServer.loopback(2)),
				hole.port(), listener);
			flush(stopping);
			stopping.shutdownGracefully(0, 1, TimeUnit.SECONDS);
			assertThat(listener.failed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
				is(Socks5CommandStatus.FAILURE));
			assertThat(hole.takesAnotherWithin(RESENT_SYN_MILLIS), is(false));
		}
	}

	@Example
	public void shouldFailWhenTheGroupHasStopped() throws Exception {
		final NioEventLoopGroup stopped = new NioEventLoopGroup(1);
		final RecordingTargetListener listener = new RecordingTargetListener();

		stopped.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
		new NettyExitConnector(stopped, 2000).connect(List.of(EchoServer.LOOPBACK), 80, listener);
		assertThat(listener.failed.get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
			is(Socks5CommandStatus.FAILURE));
	}

	@Example
	public void shouldRefuseATimeoutThatIsNotPositive() {
		for (int timeout : new int[] { 0, -1, Integer.MIN_VALUE }) {
			Expect.assertThrows(IllegalArgumentException.class, () -> new NettyExitConnector(group, timeout));
		}
	}

	@Example
	public void shouldNameTheSocksReplyForEachConnectFailure() {
		final Map<Throwable, Socks5CommandStatus> replies = new LinkedHashMap<>();

		replies.put(new ConnectTimeoutException(), Socks5CommandStatus.TTL_EXPIRED);
		replies.put(new NoRouteToHostException(), Socks5CommandStatus.HOST_UNREACHABLE);
		replies.put(new ConnectException(), Socks5CommandStatus.CONNECTION_REFUSED);
		replies.put(new SocketException("Network is unreachable"), Socks5CommandStatus.NETWORK_UNREACHABLE);
		replies.put(new BindException(), Socks5CommandStatus.NETWORK_UNREACHABLE);
		replies.put(new PortUnreachableException(), Socks5CommandStatus.NETWORK_UNREACHABLE);
		replies.put(new IOException(), Socks5CommandStatus.FAILURE);
		replies.put(new IOException(new ConnectException()), Socks5CommandStatus.FAILURE);
		replies.put(new RuntimeException(), Socks5CommandStatus.FAILURE);
		replies.forEach((cause, reply) -> assertThat(cause.toString(), NettyExitConnector.statusOf(cause),
			is(reply)));
		assertThat(NettyExitConnector.statusOf(null), is(Socks5CommandStatus.FAILURE));
	}
}
