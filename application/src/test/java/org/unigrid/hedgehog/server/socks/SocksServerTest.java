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

package org.unigrid.hedgehog.server.socks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5ClientEncoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5Message;
import java.lang.reflect.Field;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import me.alexpanov.net.FreePortFinder;
import mockit.Expectations;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.command.option.SocksOptions;
import org.unigrid.hedgehog.jqwik.Expect;
import org.unigrid.hedgehog.proxy.socks.Tunnel;
import org.unigrid.hedgehog.proxy.socks.TunnelFailure;
import org.unigrid.hedgehog.server.BaseServerTest;
import org.unigrid.hedgehog.server.TestServer;

public class SocksServerTest extends BaseServerTest {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String EVERY_INTERFACE = "0.0.0.0";

	/* Not started yet, so a test sees what init does with these options */
	private static SocksServer serverFor(String host, int port) throws ReflectiveOperationException {
		final SocksServer server = new SocksServer();
		final Field tunnel = SocksServer.class.getDeclaredField("tunnel");

		new Expectations() {{
			SocksOptions.getHost(); result = host; minTimes = 0;
			SocksOptions.getPort(); result = port; minTimes = 0;
		}};

		tunnel.setAccessible(true);
		tunnel.set(server, Tunnel.refusing(TunnelFailure.NOT_ALLOWED));
		return server;
	}

	private static EventLoopGroup groupOf(SocksServer server) throws ReflectiveOperationException {
		final Field group = SocksServer.class.getDeclaredField("group");

		group.setAccessible(true);
		return (EventLoopGroup) group.get(server);
	}

	/* The module descriptor reads logback-classic only, so the in-memory appender of logback-core is out of reach */
	private static List<ILoggingEvent> warningsWhile(Expect.Action action) throws Throwable {
		SocksServerTest.class.getModule()
			.addReads(ModuleLayer.boot().findModule("ch.qos.logback.core").orElseThrow());

		final Logger logger = (Logger) LoggerFactory.getLogger(SocksServer.class);
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

	private static void startAndStop(String host) throws ReflectiveOperationException {
		final SocksServer server = serverFor(host, FreePortFinder.findFreeLocalPort());

		server.init();
		server.destroy();
	}

	/* Answers the greeting with a CONNECT to example.org:443 and hands back the status of the reply */
	private static final class ConnectingClient extends SimpleChannelInboundHandler<Socks5Message> {
		private final CompletableFuture<Socks5CommandStatus> reply;

		ConnectingClient(CompletableFuture<Socks5CommandStatus> reply) {
			this.reply = reply;
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, Socks5Message message) {
			if (message instanceof Socks5CommandResponse response) {
				reply.complete(response.status());
			} else {
				ctx.pipeline().replace(Socks5InitialResponseDecoder.class, "command",
					new Socks5CommandResponseDecoder());
				ctx.writeAndFlush(new DefaultSocks5CommandRequest(Socks5CommandType.CONNECT,
					Socks5AddressType.DOMAIN, "example.org", 443));
			}
		}
	}

	/* A SOCKS5 client that greets with NO AUTH, asks to CONNECT example.org:443 and hands back the reply */
	private static Socks5CommandStatus connect(EventLoopGroup group, int port) throws Exception {
		final CompletableFuture<Socks5CommandStatus> reply = new CompletableFuture<>();
		final Channel channel = new Bootstrap().group(group).channel(NioSocketChannel.class)
			.handler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel channel) {
					channel.pipeline().addLast(Socks5ClientEncoder.DEFAULT,
						new Socks5InitialResponseDecoder(), new ConnectingClient(reply));
				}
			}).connect("localhost", port).sync().channel();

		try {
			channel.writeAndFlush(new DefaultSocks5InitialRequest(Socks5AuthMethod.NO_AUTH));
			return reply.get(5, TimeUnit.SECONDS);
		} finally {
			channel.close();
		}
	}

	@Property(tries = 3)
	public void shouldRefuseEveryConnectAsNotAllowedByRuleset(@ForAll("provideTestServers") List<TestServer> servers)
		throws Exception {
		final EventLoopGroup group = new NioEventLoopGroup(1);

		try {
			for (TestServer server : servers) {
				final InetSocketAddress listening = (InetSocketAddress) server.getSocks().getChannel()
					.localAddress();

				assertThat(listening.getAddress().isLoopbackAddress(), is(true));
				assertThat(connect(group, server.getSocks().getPort()), is(Socks5CommandStatus.FORBIDDEN));
			}
		} finally {
			group.shutdownGracefully();
		}
	}

	@Example
	public void shouldStayOffWhileThePortIsOff() throws ReflectiveOperationException {
		final SocksServer server = serverFor(LOOPBACK, SocksOptions.OFF);

		server.init();
		assertThat(server.isListening(), is(false));
		assertThat(server.getChannel(), nullValue());
		assertThat(groupOf(server), nullValue());
		server.destroy();
	}

	@Example
	public void shouldWarnOnlyWhenListeningBeyondLoopback() throws Throwable {
		final List<ILoggingEvent> beyond = warningsWhile(() -> startAndStop(EVERY_INTERFACE));

		assertThat(warningsWhile(() -> startAndStop(LOOPBACK)), empty());
		assertThat(beyond, hasSize(1));
		assertThat(beyond.get(0).getFormattedMessage(), containsString("asks for no credentials"));
	}

	@Example
	public void shouldBindTheHostAndPortItIsGiven() throws Exception {
		for (String host : new String[] { LOOPBACK, EVERY_INTERFACE }) {
			final int port = FreePortFinder.findFreeLocalPort();
			final SocksServer server = serverFor(host, port);

			server.init();

			final InetSocketAddress bound = (InetSocketAddress) server.getChannel().localAddress();

			assertThat(server.isListening(), is(true));
			assertThat(bound.getPort(), is(port));
			assertThat(bound.getAddress().isAnyLocalAddress(), is(host.equals(EVERY_INTERFACE)));
			assertThat(bound.getAddress().isLoopbackAddress(), is(host.equals(LOOPBACK)));
			server.destroy();
		}
	}

	@Example
	public void shouldCloseAndStopWhatItStarted() throws Exception {
		final int port = FreePortFinder.findFreeLocalPort();
		final SocksServer server = serverFor(LOOPBACK, port);

		server.init();

		final Channel channel = server.getChannel();

		server.destroy();
		assertThat(server.isListening(), is(false));
		assertThat(channel.isOpen(), is(false));
		assertThat(channel.eventLoop().parent().isTerminated(), is(true));

		new ServerSocket(port, 1, InetAddress.getByName(LOOPBACK)).close();
	}

	@Example
	public void shouldNameThePortItCannotBindAndStopItsThreads() throws Exception {
		try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
			final SocksServer server = serverFor(LOOPBACK, taken.getLocalPort());
			final IllegalStateException refused = Expect.assertThrows(IllegalStateException.class, server::init);

			assertThat(refused.getCause() instanceof BindException, is(true));
			assertThat(refused.getMessage(), containsString("127.0.0.1:" + taken.getLocalPort()));
			assertThat(refused.getMessage(), containsString("--socks-port"));
			assertThat(server.isListening(), is(false));
			assertThat(groupOf(server).isShuttingDown(), is(true));
			server.destroy();
		}
	}

	@Example
	public void shouldNameTheHostItCannotUseAndStartNothing() throws Exception {
		for (String host : new String[] { "", " ", "\t" }) {
			final SocksServer server = serverFor(host, FreePortFinder.findFreeLocalPort());
			final IllegalStateException refused = Expect.assertThrows(IllegalStateException.class, server::init);

			assertThat(refused.getMessage(), containsString("--socks-host '" + host + "'"));
			assertThat(server.isListening(), is(false));
			assertThat(groupOf(server), nullValue());
		}
	}

	@Example
	public void shouldKeepAClientAliveAndLimitHowManyItAdmits() throws Exception {
		final EventLoopGroup workers = new NioEventLoopGroup(1);

		try {
			final ServerBootstrapConfig config = serverFor(LOOPBACK, SocksOptions.OFF).bootstrap(workers).config();

			assertThat(config.childOptions().get(ChannelOption.SO_KEEPALIVE), is(true));
			assertThat(config.childHandler(), instanceOf(ConnectionLimit.class));
		} finally {
			workers.shutdownGracefully();
		}
	}
}
