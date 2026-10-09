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
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.concurrent.Future;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import static org.awaitility.Awaitility.await;
import org.unigrid.hedgehog.model.Network;

/* A QUIC connection over loopback between a server and a client that offer the given protocols, or one that a
   client tries to open towards a socket that never answers, so that its handshake never ends */
public final class QuicLoopback implements AutoCloseable {
	/* What a node of the previous release offers */
	public static final String[] PREVIOUS_RELEASE = { "hedgehog/0.0.2", "gridspork/0.0.2" };
	private static final int WAIT_SECONDS = 5;

	private final EventLoopGroup group = new NioEventLoopGroup(1);
	private final CompletableFuture<QuicChannel> pendingClientEnd = new CompletableFuture<>();
	private final CompletableFuture<QuicChannel> serverEnd;
	private final Channel server;
	private final Channel client;
	private final Future<QuicChannel> clientEnd;

	private QuicLoopback(CompletableFuture<QuicChannel> serverEnd, ChannelHandler serverCodec,
		String... clientProtocols) {

		this.serverEnd = serverEnd;
		server = bind(serverCodec);
		client = bind(new QuicClientCodecBuilder()
			.sslContext(QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
				.applicationProtocols(clientProtocols).build())
			.initialMaxData(Network.MAX_DATA_SIZE)
			.initialMaxStreamDataBidirectionalLocal(Network.MAX_DATA_SIZE)
			.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
			.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES).build());

		clientEnd = QuicChannel.newBootstrap(client).handler(new ChannelInboundHandlerAdapter() {
			@Override public void handlerAdded(ChannelHandlerContext ctx) {
				pendingClientEnd.complete((QuicChannel) ctx.channel());
			}
		}).streamHandler(new ChannelInboundHandlerAdapter()).remoteAddress(server.localAddress()).connect();
	}

	@SneakyThrows
	public static QuicLoopback between(String[] serverProtocols, String[] clientProtocols) {
		final SelfSignedCertificate certificate = new SelfSignedCertificate();
		final CompletableFuture<QuicChannel> serverEnd = new CompletableFuture<>();

		return new QuicLoopback(serverEnd, new QuicServerCodecBuilder()
			.sslContext(QuicSslContextBuilder.forServer(certificate.privateKey(), null,
				certificate.certificate()).applicationProtocols(serverProtocols).build())
			.tokenHandler(InsecureQuicTokenHandler.INSTANCE)
			.initialMaxData(Network.MAX_DATA_SIZE)
			.initialMaxStreamDataBidirectionalRemote(Network.MAX_DATA_SIZE)
			.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
			.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES)
			.handler(new ChannelInboundHandlerAdapter() {
				/* A connection becomes active once its handshake is done */
				@Override public void channelActive(ChannelHandlerContext ctx) {
					serverEnd.complete((QuicChannel) ctx.channel());
					ctx.fireChannelActive();
				}
			})
			.streamHandler(new ChannelInboundHandlerAdapter()).build(), clientProtocols);
	}

	public static QuicLoopback unanswered(String... clientProtocols) {
		return new QuicLoopback(new CompletableFuture<>(), new ChannelInboundHandlerAdapter(), clientProtocols);
	}

	@SneakyThrows
	private Channel bind(ChannelHandler codec) {
		return new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(codec)
			.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
	}

	/* Once its handshake is done */
	@SneakyThrows
	public QuicChannel clientEnd() {
		return clientEnd.get(WAIT_SECONDS, TimeUnit.SECONDS);
	}

	/* Once it has started its handshake */
	@SneakyThrows
	public QuicChannel pendingClientEnd() {
		final QuicChannel connection = pendingClientEnd.get(WAIT_SECONDS, TimeUnit.SECONDS);

		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> connection.sslEngine() != null);
		return connection;
	}

	@SneakyThrows
	public QuicChannel serverEnd() {
		return serverEnd.get(WAIT_SECONDS, TimeUnit.SECONDS);
	}

	@SneakyThrows
	public QuicStreamChannel stream() {
		return clientEnd().createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter()).sync()
			.getNow();
	}

	@Override
	public void close() throws InterruptedException {
		client.close().sync();
		server.close().sync();
		group.shutdownGracefully(0, WAIT_SECONDS, TimeUnit.SECONDS).sync();
	}
}
