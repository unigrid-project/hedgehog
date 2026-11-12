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

package org.unigrid.hedgehog.model.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicConnectionCloseEvent;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.Network;

/* The connection that a node dialed, with the event loop that it runs on, and the peer that it reached, which has a
   loop of its own, over a QUIC connection between two sockets of this machine: a peer learns that a connection is
   closed only when it is told, and holds one that it was told nothing of until it idles out, minutes later. */
public class ConnectionContainerTest {
	private static final String PROTOCOL = "hedgehog/test";
	private static final Duration WAIT = Duration.ofSeconds(10);

	private final EventLoopGroup peerGroup = new NioEventLoopGroup(1);
	private final EventLoopGroup group = new NioEventLoopGroup(1);
	private final CompletableFuture<QuicConnectionCloseEvent> told = new CompletableFuture<>();

	@AfterTry
	public void stop() throws InterruptedException {
		group.shutdownGracefully(0, WAIT.toSeconds(), TimeUnit.SECONDS).sync();
		peerGroup.shutdownGracefully(0, WAIT.toSeconds(), TimeUnit.SECONDS).sync();
	}

	private static Channel bind(EventLoopGroup group, ChannelHandler codec) throws InterruptedException {
		return new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(codec)
			.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
	}

	/* The peer notes the event by which the codec tells that the other end closed the connection */
	private ChannelHandler peer() throws Exception {
		final SelfSignedCertificate certificate = new SelfSignedCertificate();

		try {
			return new QuicServerCodecBuilder()
				.sslContext(QuicSslContextBuilder.forServer(certificate.privateKey(), null, certificate.certificate())
					.applicationProtocols(PROTOCOL).build())
				.tokenHandler(InsecureQuicTokenHandler.INSTANCE)
				.initialMaxData(Network.MAX_WINDOW_SIZE)
				.initialMaxStreamDataBidirectionalRemote(Network.MAX_WINDOW_SIZE)
				.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
				.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES)
				.handler(new ChannelInboundHandlerAdapter() {
					@Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
						if (event instanceof QuicConnectionCloseEvent closed) {
							told.complete(closed);
						}

						ctx.fireUserEventTriggered(event);
					}
				}).streamHandler(new ChannelInboundHandlerAdapter()).build();
		} finally {
			certificate.delete();
		}
	}

	private static ChannelHandler node() {
		return new QuicClientCodecBuilder()
			.sslContext(QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
				.applicationProtocols(PROTOCOL).build())
			.initialMaxData(Network.MAX_WINDOW_SIZE)
			.initialMaxStreamDataBidirectionalLocal(Network.MAX_WINDOW_SIZE)
			.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
			.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES).build();
	}

	/* The main stream of a connection to the peer, on the loop of the node */
	private QuicStreamChannel dial() throws Exception {
		final QuicChannel connection = QuicChannel.newBootstrap(bind(group, node()))
			.streamHandler(new ChannelInboundHandlerAdapter()).remoteAddress(bind(peerGroup, peer()).localAddress())
			.connect().get(WAIT.toSeconds(), TimeUnit.SECONDS);

		return connection.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter()).sync()
			.getNow();
	}

	private void tellsThePeerWith(Consumer<ConnectionContainer> close) throws Exception {
		final ConnectionContainer dialed = ConnectionContainer.builder().channel(dial()).group(Optional.of(group))
			.build();

		close.accept(dialed);

		await("the peer to be told that the connection is closed").atMost(WAIT).until(told::isDone);
		await("the loop of the connection to end").atMost(WAIT).until(group::isTerminated);
	}

	@Example
	public void tellsThePeerThatTheConnectionItDialedIsClosed() throws Exception {
		tellsThePeerWith(ConnectionContainer::close);
	}

	@Example
	public void tellsThePeerThatTheConnectionItDialedIsClosedWithoutWaitingForItsEnd() throws Exception {
		tellsThePeerWith(ConnectionContainer::closeDirty);
	}

	/* A stream that a peer opened runs on a connection and a loop that the server holds, with the other streams of
	   that peer */
	@Example
	public void leavesTheConnectionOfAStreamThatAPeerOpenedOpen() throws Exception {
		final QuicStreamChannel stream = dial();

		ConnectionContainer.builder().channel(stream).build().close();

		assertThat(stream.isShutdown(), is(true));
		assertThat(stream.parent().isOpen(), is(true));
		assertThat(told.isDone(), is(false));
	}
}
