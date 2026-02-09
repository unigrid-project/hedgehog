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

package org.unigrid.hedgehog.proxy.exit;

import io.netty.channel.ConnectTimeoutException;
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/* Real connections, all of them to 127.0.0.0/8 */
public class NettyExitConnectorTest {
	private static final byte[] HELLO = "hello".getBytes(StandardCharsets.US_ASCII);
	private static final long QUIET_MILLIS = 200;
	private static final int SHORT_TIMEOUT_MILLIS = 300;
	private static final int MAX_QUEUED = 16;

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

	/* A listener whose accept queue is full drops new connection attempts, so a connect to it can only time out.
	   The sockets that fill the queue stay open until the caller closes them. */
	private static List<Socket> fillAcceptQueue(ServerSocket server) throws IOException {
		final List<Socket> queued = new ArrayList<>();

		for (int i = 0; i < MAX_QUEUED; i++) {
			final Socket socket = new Socket();

			try {
				socket.connect(server.getLocalSocketAddress(), SHORT_TIMEOUT_MILLIS);
				queued.add(socket);
			} catch (SocketTimeoutException full) {
				socket.close();
				return queued;
			}
		}

		throw new AssertionError("The accept queue still takes connections after " + MAX_QUEUED);
	}

	private static void closeAll(List<Socket> sockets) throws IOException {
		for (Socket socket : sockets) {
			socket.close();
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

		try (ServerSocket full = new ServerSocket(0, 1, EchoServer.LOOPBACK)) {
			final List<Socket> queued = fillAcceptQueue(full);
			final RecordingTargetListener timedOutLast = new RecordingTargetListener();
			final RecordingTargetListener refusedLast = new RecordingTargetListener();

			try {
				impatient.connect(List.of(EchoServer.loopback(10), EchoServer.LOOPBACK), full.getLocalPort(),
					timedOutLast);
				impatient.connect(List.of(EchoServer.LOOPBACK, EchoServer.loopback(10)), full.getLocalPort(),
					refusedLast);
				assertThat(timedOutLast.failed.get(RecordingTargetListener.TIMEOUT_MILLIS,
					TimeUnit.MILLISECONDS), is(Socks5CommandStatus.TTL_EXPIRED));
				assertThat(refusedLast.failed.get(RecordingTargetListener.TIMEOUT_MILLIS,
					TimeUnit.MILLISECONDS), is(Socks5CommandStatus.CONNECTION_REFUSED));
			} finally {
				closeAll(queued);
			}
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
