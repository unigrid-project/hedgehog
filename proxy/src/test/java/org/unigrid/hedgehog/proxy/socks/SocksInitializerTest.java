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

package org.unigrid.hedgehog.proxy.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/* Raw SOCKS5 bytes through the whole pipeline, as a client such as curl sends them */
public class SocksInitializerTest {
	private static final byte[] NO_AUTH_GREETING = { 5, 1, 0 };
	private static final byte[] EMPTY_CREDENTIALS = { 1, 0, 0 };
	private static final byte[] CONNECT_EXAMPLE_ORG = { 5, 1, 0, 3, 11, 'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'o',
		'r', 'g', 1, (byte) 0xbb };
	private static final SocksAddress BOUND = new SocksAddress(Socks5AddressType.IPv4, "192.0.2.7", 40_000);
	private static final byte[] UDP_ASSOCIATE_ANY = { 5, 3, 0, 1, 0, 0, 0, 0, 0, 0 };
	private static final int WAIT_MILLIS = 5000;

	/* Where a connection stands when its client resets it: before the greeting, before the command, waiting for the
	   tunnel's answer and relaying */
	private enum Stage {
		NEW, GREETED(NO_AUTH_GREETING), COMMANDED(NO_AUTH_GREETING, CONNECT_EXAMPLE_ORG), RELAYING;

		private final byte[][] sent;

		Stage(byte[]... sent) {
			this.sent = sent;
		}
	}

	/* How far a real client gets before it resets its connection: what it sends, and how many bytes of replies it
	   reads back first */
	private enum Reset {
		GREETED(2, NO_AUTH_GREETING), RELAYING(12, NO_AUTH_GREETING, CONNECT_EXAMPLE_ORG),
		ASSOCIATED(12, NO_AUTH_GREETING, UDP_ASSOCIATE_ANY);

		private final int replyBytes;
		private final byte[][] sent;

		Reset(int replyBytes, byte[]... sent) {
			this.replyBytes = replyBytes;
			this.sent = sent;
		}
	}

	/* Notes every failure that reaches it, and passes it on unless it stands for the tail of the pipeline */
	private static final class Failures extends ChannelInboundHandlerAdapter {
		private final BlockingQueue<Throwable> seen = new LinkedBlockingQueue<>();
		private final boolean passesOn;

		Failures(boolean passesOn) {
			this.passesOn = passesOn;
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			seen.add(cause);

			if (passesOn) {
				ctx.fireExceptionCaught(cause);
			}
		}
	}

	/* Answers every stream and association at once, on the event loop of the connection that asked */
	private static final class AnsweringTunnel implements Tunnel {
		@Override
		public TunnelStream open(SocksAddress destination, TunnelListener listener) {
			listener.connected(BOUND);
			return new EchoTunnel.EchoStream(destination, listener);
		}

		@Override
		public AssociationStream associate(AssociationListener listener) {
			listener.opened();
			return new EchoTunnel.EchoAssociation(listener);
		}
	}

	private static EmbeddedChannel connected(EchoTunnel tunnel) {
		final EmbeddedChannel channel = new EmbeddedChannel(new SocksInitializer(tunnel));

		channel.writeInbound(Unpooled.wrappedBuffer(NO_AUTH_GREETING));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(new byte[] { 5, 0 }));
		channel.writeInbound(Unpooled.wrappedBuffer(CONNECT_EXAMPLE_ORG));
		tunnel.stream().connect(BOUND);
		channel.runPendingTasks();
		assertThat(SocksCommandTest.readBytes(channel), equalTo(new byte[] { 5, 0, 0, 1, (byte) 192, 0, 2, 7,
			(byte) 0x9c, 0x40 }));
		return channel;
	}

	@Provide
	public Arbitrary<List<byte[]>> chunks() {
		return Arbitraries.bytes().array(byte[].class).ofMinSize(1).ofMaxSize(3000).list().ofMaxSize(10);
	}

	@Example
	public void shouldOpenTheDestinationTheClientNamed() {
		final EchoTunnel tunnel = new EchoTunnel();

		connected(tunnel);
		assertThat(tunnel.stream().destination, equalTo(new SocksAddress(Socks5AddressType.DOMAIN, "example.org",
			443)));
	}

	@Property(tries = 30)
	public void shouldRelayAStreamInOrder(@ForAll("chunks") List<byte[]> chunks) {
		final EchoTunnel tunnel = new EchoTunnel();
		final EmbeddedChannel channel = connected(tunnel);
		final ByteArrayOutputStream sent = new ByteArrayOutputStream();

		for (byte[] chunk : chunks) {
			channel.writeInbound(Unpooled.wrappedBuffer(chunk));
			sent.writeBytes(chunk);
		}

		channel.runPendingTasks();
		assertThat(tunnel.stream().written.toByteArray(), equalTo(sent.toByteArray()));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(sent.toByteArray()));
	}

	@Example
	public void shouldAnswerARefusedStreamAndHangUp() {
		final EchoTunnel tunnel = new EchoTunnel();
		final EmbeddedChannel channel = new EmbeddedChannel(new SocksInitializer(tunnel));

		channel.writeInbound(Unpooled.wrappedBuffer(NO_AUTH_GREETING, CONNECT_EXAMPLE_ORG));
		tunnel.stream().fail(TunnelFailure.NOT_ALLOWED);
		channel.runPendingTasks();
		assertThat(SocksCommandTest.readBytes(channel), equalTo(new byte[] { 5, 0, 5, 2, 0, 1, 0, 0, 0, 0, 0, 0 }));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldTurnAwayAClientThatInsistsOnAMethodItDoesNotServe() {
		final EmbeddedChannel channel = new EmbeddedChannel(new SocksInitializer(new EchoTunnel()));

		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 5, 1, 1 }));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(new byte[] { 5, (byte) 0xff }));
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldServeAClientThatOffersBothMethodsWithoutACountry() {
		final EchoTunnel tunnel = new EchoTunnel();
		final EmbeddedChannel channel = new EmbeddedChannel(new SocksInitializer(tunnel));

		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 5, 2, 0, 2 }, EMPTY_CREDENTIALS,
			CONNECT_EXAMPLE_ORG));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(new byte[] { 5, 2, 1, 0 }));
		assertThat(tunnel.streams.size(), is(1));
		assertThat(channel.isOpen(), is(true));
	}

	@Example
	public void shouldTurnAwayAClientThatNamesAnUnknownCountry() {
		final EchoTunnel tunnel = new EchoTunnel();
		final EmbeddedChannel channel = new EmbeddedChannel(new SocksInitializer(tunnel));

		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 5, 1, 2 }, new byte[] { 1, 2, 'X', 'X', 0 },
			CONNECT_EXAMPLE_ORG));
		assertThat(SocksCommandTest.readBytes(channel), equalTo(new byte[] { 5, 2, 1, (byte) 0xff }));
		assertThat(tunnel.streams, empty());
		assertThat(channel.isOpen(), is(false));
	}

	/* The clock of an embedded channel runs on its own unless frozen before the deadline is set, and only a frozen
	   clock lets the test probe the exact edge of the deadline */
	private static EmbeddedChannel frozen(Tunnel tunnel) {
		final EmbeddedChannel channel = new EmbeddedChannel();

		channel.freezeTime();
		channel.pipeline().addLast(new SocksInitializer(tunnel));
		return channel;
	}

	/* The 10 seconds are written out on purpose: the README promises a client ten seconds for its greeting and
	   command, so a change of the constant must fail here */
	private static void assertHangsUpWhenTheDeadlinePasses(EmbeddedChannel channel) {
		channel.advanceTimeBy(TimeUnit.SECONDS.toMillis(10) - 1, TimeUnit.MILLISECONDS);
		channel.runScheduledPendingTasks();
		assertThat(channel.isOpen(), is(true));

		channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
		channel.runScheduledPendingTasks();
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldHangUpOnAClientThatSendsNothing() {
		assertHangsUpWhenTheDeadlinePasses(frozen(new EchoTunnel()));
	}

	@Example
	public void shouldHangUpOnAClientThatStallsInTheMiddleOfItsGreeting() {
		final EmbeddedChannel channel = frozen(new EchoTunnel());

		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 5, 2, 0 }));
		assertHangsUpWhenTheDeadlinePasses(channel);
	}

	@Example
	public void shouldHangUpOnAClientThatStallsInTheMiddleOfItsCredentials() {
		final EmbeddedChannel channel = frozen(new EchoTunnel());

		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 5, 1, 2 }));
		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1, 2, 'S' }));
		assertHangsUpWhenTheDeadlinePasses(channel);
	}

	@Example
	public void shouldHangUpOnAClientThatStallsBetweenItsCredentialsAndItsCommand() {
		final EmbeddedChannel channel = frozen(new EchoTunnel());

		channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 5, 1, 2 }, EMPTY_CREDENTIALS));
		assertHangsUpWhenTheDeadlinePasses(channel);
	}

	@Example
	public void shouldHangUpOnAClientThatStallsInTheMiddleOfItsCommand() {
		final EmbeddedChannel channel = frozen(new EchoTunnel());

		channel.writeInbound(Unpooled.wrappedBuffer(NO_AUTH_GREETING));
		channel.writeInbound(Unpooled.wrappedBuffer(CONNECT_EXAMPLE_ORG, 0, 8));
		assertHangsUpWhenTheDeadlinePasses(channel);
	}

	@Example
	public void shouldCancelTheDeadlineWhenTheHandlerLeavesThePipeline() {
		final EmbeddedChannel channel = new EmbeddedChannel(new SocksInitializer(new EchoTunnel()));

		assertThat(channel.runScheduledPendingTasks() >= 0, is(true));
		channel.pipeline().remove(HandshakeTimeout.class);
		assertThat(channel.runScheduledPendingTasks(), is(-1L));
	}

	@Example
	public void shouldLeaveTheTunnelToBoundItsOwnAnswerOnceTheCommandIsAccepted() {
		final EchoTunnel tunnel = new EchoTunnel();
		final EmbeddedChannel channel = new EmbeddedChannel(new SocksInitializer(tunnel));

		channel.writeInbound(Unpooled.wrappedBuffer(NO_AUTH_GREETING, CONNECT_EXAMPLE_ORG));
		channel.advanceTimeBy(SocksInitializer.HANDSHAKE_TIMEOUT_MILLIS * 100, TimeUnit.MILLISECONDS);
		channel.runScheduledPendingTasks();
		assertThat(channel.isOpen(), is(true));
		assertThat(tunnel.stream().destination, equalTo(new SocksAddress(Socks5AddressType.DOMAIN, "example.org",
			443)));
	}

	@Example
	public void shouldHangUpWhenTheDestinationDoes() {
		final EchoTunnel tunnel = new EchoTunnel();
		final EmbeddedChannel channel = connected(tunnel);

		tunnel.stream().finish();
		channel.runPendingTasks();
		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void shouldServeEveryConnectionOfAListenerWithItsOwnHandlers() {
		final EchoTunnel tunnel = new EchoTunnel();
		final SocksInitializer initializer = new SocksInitializer(tunnel);
		final EmbeddedChannel first = new EmbeddedChannel(initializer);
		final EmbeddedChannel second = new EmbeddedChannel(initializer);

		first.writeInbound(Unpooled.wrappedBuffer(NO_AUTH_GREETING));
		second.writeInbound(Unpooled.wrappedBuffer(NO_AUTH_GREETING, CONNECT_EXAMPLE_ORG));
		assertThat(tunnel.streams.size(), is(1));
		first.writeInbound(Unpooled.wrappedBuffer(CONNECT_EXAMPLE_ORG));
		assertThat(tunnel.streams.size(), is(2));
		assertThat(SocksCommandTest.readBytes(first), equalTo(new byte[] { 5, 0 }));
		assertThat(SocksCommandTest.readBytes(second), equalTo(new byte[] { 5, 0 }));
	}

	private static EmbeddedChannel reached(Stage stage, EchoTunnel tunnel) {
		final EmbeddedChannel channel = stage == Stage.RELAYING ? connected(tunnel)
			: new EmbeddedChannel(new SocksInitializer(tunnel));

		for (byte[] bytes : stage.sent) {
			channel.writeInbound(Unpooled.wrappedBuffer(bytes));
		}

		return channel;
	}

	/* A client that resets its connection, as browsers do, fails a read wherever the connection stands, and a
	   failure that reaches the tail of the pipeline is logged as a warning with its stack trace. One that a handler
	   raises goes on from that handler, so the handler that stops it has to stay the last. */
	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldCloseQuietlyWhenTheClientResetsTheConnection(@ForAll Stage stage) {
		final EmbeddedChannel channel = reached(stage, new EchoTunnel());

		assertThat(channel.pipeline().last(), instanceOf(QuietClose.class));
		channel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));
		channel.checkException();

		assertThat(channel.isOpen(), is(false));
	}

	/* A real client resets its connection after its greeting, while relaying, and on the control connection of a UDP
	   association, whose socket only a real event loop binds. The failure passes the first handler and has to stop
	   before the last, which the test puts behind those of the listener. */
	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldCloseQuietlyWhenARealClientResetsTheConnection(@ForAll Reset reset) throws Exception {
		final NioEventLoopGroup group = new NioEventLoopGroup(1);
		final Failures first = new Failures(true);
		final Failures last = new Failures(false);
		final CompletableFuture<Channel> accepted = new CompletableFuture<>();

		try {
			final Channel listener = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<Channel>() {
					@Override
					protected void initChannel(Channel channel) {
						channel.pipeline().addLast(new SocksInitializer(new AnsweringTunnel())).addLast(last)
							.addFirst(first);
						accepted.complete(channel);
					}
				}).bind(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 0).sync().channel();

			try (Socket client = new Socket()) {
				client.connect(listener.localAddress(), WAIT_MILLIS);
				client.setSoTimeout(WAIT_MILLIS);

				for (byte[] bytes : reset.sent) {
					client.getOutputStream().write(bytes);
				}

				assertThat(client.getInputStream().readNBytes(reset.replyBytes).length, is(reset.replyBytes));
				client.setSoLinger(true, 0);
			}

			assertThat(first.seen.poll(WAIT_MILLIS, TimeUnit.MILLISECONDS), instanceOf(IOException.class));
			assertThat(accepted.get(WAIT_MILLIS, TimeUnit.MILLISECONDS).closeFuture().await(WAIT_MILLIS), is(true));
			assertThat(last.seen, empty());
		} finally {
			group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
		}
	}

	@Example
	public void shouldRefuseToServeWithoutATunnel() {
		Expect.assertThrows(NullPointerException.class, () -> new SocksInitializer(null));
	}
}
