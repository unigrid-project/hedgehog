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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterProperty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/* Real sockets, all of them on 127.0.0.1 */
public class NettyUdpConnectorTest {
	private static final long WAIT_MILLIS = RecordingTargetListener.TIMEOUT_MILLIS;
	/* The largest payload an IPv4 datagram carries */
	private static final int LARGEST_DATAGRAM = 65_507;
	/* Netty's default high water mark of what waits to be written is 64 KiB */
	private static final int PIECE = 16 * 1024;

	private final NioEventLoopGroup group = new NioEventLoopGroup(1);
	private final UdpConnector connector = new NettyUdpConnector(group);

	private static final class Heard implements UdpConnector.Listener {
		final CompletableFuture<UdpConnector.Socket> opened = new CompletableFuture<>();
		final CompletableFuture<Boolean> closed = new CompletableFuture<>();
		final List<InetSocketAddress> sources = new CopyOnWriteArrayList<>();
		final List<byte[]> datagrams = new CopyOnWriteArrayList<>();
		final List<InetSocketAddress> asked = new CopyOnWriteArrayList<>();
		volatile boolean accepting = true;
		volatile boolean failing;

		@Override
		public void opened(UdpConnector.Socket socket) {
			opened.complete(socket);
		}

		@Override
		public void failed(Socks5CommandStatus status) {
			opened.completeExceptionally(new IllegalStateException(status.toString()));
		}

		@Override
		public boolean accepts(InetSocketAddress source) {
			asked.add(source);
			return accepting;
		}

		@Override
		public void received(InetSocketAddress source, byte[] bytes) {
			if (failing) {
				throw new IllegalStateException("The listener failed");
			}

			sources.add(source);
			datagrams.add(bytes);
		}

		@Override
		public void closed() {
			closed.complete(true);
		}

		UdpConnector.Socket socket() throws Exception {
			return opened.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		}

		byte[] first() throws InterruptedException {
			return received(1);
		}

		byte[] received(int count) throws InterruptedException {
			RecordingTargetListener.await(() -> datagrams.size() >= count);
			return datagrams.get(count - 1);
		}
	}

	/* A buffer that notes when its bytes are read */
	private static final class Watched extends UnpooledHeapByteBuf {
		private boolean read;

		Watched(byte[] bytes) {
			super(UnpooledByteBufAllocator.DEFAULT, bytes, bytes.length);
		}

		@Override
		public byte[] array() {
			read = true;
			return super.array();
		}

		@Override
		public ByteBuf getBytes(int index, byte[] destination, int offset, int length) {
			read = true;
			return super.getBytes(index, destination, offset, length);
		}
	}

	@AfterProperty
	public void stopEventLoop() {
		group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
	}

	/* A task of its own on the event loop runs after the read of anything that had already arrived */
	private void settle() throws Exception {
		group.next().submit(() -> { }).get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
	}

	private static void sendTo(UdpConnector.Socket socket, byte[] bytes) throws IOException {
		try (DatagramSocket peer = new DatagramSocket(0, EchoServer.LOOPBACK)) {
			peer.send(new java.net.DatagramPacket(bytes, bytes.length, EchoServer.LOOPBACK,
				socket.localAddress().getPort()));
		}
	}

	@Example
	public void shouldBindAnEphemeralPortAndSendToTheAddressGiven() throws Exception {
		try (UdpEcho echo = new UdpEcho()) {
			final Heard heard = new Heard();

			connector.open(heard);

			final UdpConnector.Socket socket = heard.socket();

			assertThat(socket.localAddress().getPort(), greaterThan(0));
			assertThat(socket.localAddress().getAddress().isAnyLocalAddress(), is(true));
			socket.setReadable(true);
			assertThat(socket.send(echo.address(), echo.port(), new byte[] { 1, 2, 3 }), is(true));
			assertThat(heard.first(), equalTo(new byte[] { 1, 2, 3 }));
			assertThat(heard.sources.get(0), equalTo(new InetSocketAddress(echo.address(), echo.port())));
			assertThat(heard.asked, equalTo(heard.sources));
		}
	}

	@Example
	public void shouldReadNothingUntilAskedTo() throws Exception {
		final Heard heard = new Heard();

		connector.open(heard);
		sendTo(heard.socket(), new byte[] { 1 });
		settle();
		assertThat(heard.asked, is(empty()));
		assertThat(heard.datagrams, is(empty()));
		heard.socket().setReadable(true);
		assertThat(heard.first(), equalTo(new byte[] { 1 }));
	}

	@Example
	public void shouldStopReadingWhenToldAndGoOnWhenToldAgain() throws Exception {
		final Heard heard = new Heard();

		connector.open(heard);

		final UdpConnector.Socket socket = heard.socket();

		socket.setReadable(true);
		sendTo(socket, new byte[] { 1 });
		heard.first();
		socket.setReadable(false);
		settle();
		sendTo(socket, new byte[] { 2 });
		settle();
		assertThat(heard.asked.size(), is(1));
		socket.setReadable(true);
		assertThat(heard.received(2), equalTo(new byte[] { 2 }));
	}

	@Example
	public void shouldReadAWholeLargestDatagram() throws Exception {
		try (UdpEcho echo = new UdpEcho()) {
			final Heard heard = new Heard();
			final byte[] datagram = new byte[LARGEST_DATAGRAM];

			datagram[LARGEST_DATAGRAM - 1] = 7;
			connector.open(heard);
			heard.socket().setReadable(true);
			heard.socket().send(echo.address(), echo.port(), datagram);
			assertThat(heard.first(), equalTo(datagram));
		}
	}

	@Example
	public void shouldTellWhenTheSocketCloses() throws Exception {
		final Heard heard = new Heard();

		connector.open(heard);
		heard.socket().close();
		assertThat(heard.closed.get(WAIT_MILLIS, TimeUnit.MILLISECONDS), is(true));
	}

	@Example
	public void shouldDropWhatItDoesNotAcceptWithoutReadingIt() throws Exception {
		final Heard heard = new Heard();

		heard.accepting = false;
		connector.open(heard);
		heard.socket().setReadable(true);
		sendTo(heard.socket(), new byte[] { 1 });
		RecordingTargetListener.await(() -> !heard.asked.isEmpty());
		settle();
		assertThat(heard.datagrams, is(empty()));
	}

	/* A stranger's datagram must cost no copy of its content, and every buffer goes back whichever way it went */
	@Example
	public void shouldNotReadTheDatagramOfASourceItRefuses() {
		final Heard heard = new Heard();
		final EmbeddedChannel channel = new EmbeddedChannel(new NettyUdpConnector.Receiver(heard));
		final InetSocketAddress here = new InetSocketAddress(EchoServer.LOOPBACK, 1);
		final InetSocketAddress there = new InetSocketAddress(EchoServer.LOOPBACK, 2);
		final Watched refused = new Watched(new byte[] { 1 });
		final Watched accepted = new Watched(new byte[] { 2 });

		heard.accepting = false;
		channel.writeInbound(new DatagramPacket(refused, here, there));
		heard.accepting = true;
		channel.writeInbound(new DatagramPacket(accepted, here, there));

		assertThat(refused.read, is(false));
		assertThat(accepted.read, is(true));
		assertThat(heard.datagrams.size(), is(1));
		assertThat(heard.datagrams.get(0), equalTo(new byte[] { 2 }));
		assertThat(heard.asked, equalTo(List.of(there, there)));
		assertThat(refused.refCnt() + accepted.refCnt(), is(0));
	}

	/* A listener that throws costs the datagram it was handed, not the socket */
	@Example
	public void shouldKeepTheSocketWhenTheListenerFails() {
		final Heard heard = new Heard();
		final EmbeddedChannel channel = new EmbeddedChannel(new NettyUdpConnector.Receiver(heard));
		final Watched content = new Watched(new byte[] { 1 });

		heard.failing = true;
		channel.writeInbound(new DatagramPacket(content, new InetSocketAddress(EchoServer.LOOPBACK, 1),
			new InetSocketAddress(EchoServer.LOOPBACK, 2)));

		assertThat(channel.isOpen(), is(true));
		assertThat(content.refCnt(), is(0));
	}

	/* Runs the work on a thread of its own while the event loop is held by a task, and lets the loop go however the
	   work ends, so that work which waits for the loop fails the test instead of hanging it */
	private <T> T whileTheLoopIsBusy(Supplier<T> work) throws Exception {
		final CountDownLatch busy = new CountDownLatch(1);

		group.execute(() -> {
			try {
				busy.await();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});

		try {
			return CompletableFuture.supplyAsync(work).get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		} finally {
			busy.countDown();
		}
	}

	/* The relay calls the socket under its lock while the event loop may wait for that lock to hand over a
	   datagram, so nothing here may wait for the loop */
	@Example
	public void shouldNeverWaitForTheEventLoop() throws Exception {
		try (UdpEcho echo = new UdpEcho()) {
			final Heard heard = new Heard();

			connector.open(heard);

			final UdpConnector.Socket socket = heard.socket();

			assertThat(whileTheLoopIsBusy(() -> {
				socket.setReadable(true);
				socket.setReadable(false);

				final boolean sent = socket.send(echo.address(), echo.port(), new byte[] { 1 });

				socket.close();
				return sent;
			}), is(true));
			assertThat(heard.closed.get(WAIT_MILLIS, TimeUnit.MILLISECONDS), is(true));
		}
	}

	/* What waits for the busy event loop counts against the channel's water mark, so a relay cannot queue at the
	   socket without bound */
	@Example
	public void shouldRefuseToSendWhileTooMuchWaits() throws Exception {
		try (UdpEcho echo = new UdpEcho()) {
			final Heard heard = new Heard();

			connector.open(heard);

			final UdpConnector.Socket socket = heard.socket();
			final List<Boolean> sent = whileTheLoopIsBusy(() -> IntStream.range(0, 8)
				.mapToObj(i -> socket.send(echo.address(), echo.port(), new byte[PIECE])).toList());

			assertThat(sent.get(0), is(true));
			assertThat(sent, hasItem(false));
			RecordingTargetListener.await(() -> socket.send(echo.address(), echo.port(), new byte[] { 1 }));
		}
	}

	/* A null address would be the wildcard, which reaches the local host */
	@Example
	public void shouldRefuseWhatItCannotSendWithoutThrowing() throws Exception {
		try (UdpEcho echo = new UdpEcho()) {
			final Heard heard = new Heard();

			connector.open(heard);

			final UdpConnector.Socket socket = heard.socket();

			assertThat(socket.send(null, echo.port(), new byte[] { 1 }), is(false));
			assertThat(socket.send(echo.address(), 65_536, new byte[] { 1 }), is(false));
			assertThat(socket.send(echo.address(), echo.port(), null), is(false));
			socket.close();
			heard.closed.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
			assertThat(socket.send(echo.address(), echo.port(), new byte[] { 1 }), is(false));
		}
	}

	/* A group that is shut down would never answer the bind, so the listener hears of it at once */
	@Example
	public void shouldFailAtOnceWithoutAGroupToRunOn() throws Exception {
		final NioEventLoopGroup stopped = new NioEventLoopGroup(1);
		final Heard onStopped = new Heard();
		final Heard onNone = new Heard();

		stopped.shutdownGracefully(0, 0, TimeUnit.SECONDS).get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		new NettyUdpConnector(stopped).open(onStopped);
		new NettyUdpConnector(() -> null).open(onNone);

		assertThat(failure(onStopped), equalTo(Socks5CommandStatus.FAILURE.toString()));
		assertThat(failure(onNone), equalTo(Socks5CommandStatus.FAILURE.toString()));
	}

	/* A loop of another kind cannot run an NIO channel, so the socket is never bound */
	@Example
	public void shouldTellWhenTheSocketCannotBeBound() throws Exception {
		final DefaultEventLoopGroup foreign = new DefaultEventLoopGroup(1);
		final Heard heard = new Heard();

		try {
			new NettyUdpConnector(foreign).open(heard);
			RecordingTargetListener.await(heard.opened::isDone);
			assertThat(failure(heard), equalTo(Socks5CommandStatus.FAILURE.toString()));
		} finally {
			foreign.shutdownGracefully(0, 1, TimeUnit.SECONDS);
		}
	}

	private static String failure(Heard heard) throws InterruptedException, TimeoutException {
		assertThat(heard.opened.isDone(), is(true));

		try {
			heard.opened.get(0, TimeUnit.MILLISECONDS);
			throw new AssertionError("Opened");
		} catch (ExecutionException ex) {
			return ex.getCause().getMessage();
		}
	}
}
