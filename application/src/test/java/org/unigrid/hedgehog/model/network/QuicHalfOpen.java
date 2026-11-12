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
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicConnectionEvent;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.model.Network;

/* A QUIC server on the given address, and a client that goes quiet as soon as that server has begun its handshake, so
   that the connection the server holds to it is never done and never becomes active, as one of a peer that gave up on
   it or was never real. The handler given runs on that connection as a node's own do. */
public final class QuicHalfOpen implements AutoCloseable {
	private static final String PROTOCOL = "half-open";
	private static final int WAIT_SECONDS = 5;

	private final EventLoopGroup group = new NioEventLoopGroup(1);
	private final CompletableFuture<QuicChannel> begun = new CompletableFuture<>();
	private final AtomicBoolean quiet = new AtomicBoolean();
	private final Channel server;
	private final Channel client;

	/* The server tells that the connection has begun once the handlers behind this one have seen its address */
	private final class Beginning extends ChannelInboundHandlerAdapter {
		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			super.userEventTriggered(ctx, evt);

			if (evt instanceof QuicConnectionEvent) {
				quiet.set(true);
				begun.complete((QuicChannel) ctx.channel());
			}
		}
	}

	/* Holds back what the client would answer with, which the server's handshake waits for */
	private final class Silence extends ChannelOutboundHandlerAdapter {
		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
			if (quiet.get()) {
				ReferenceCountUtil.release(msg);
				promise.setSuccess();
			} else {
				ctx.write(msg, promise);
			}
		}
	}

	@SneakyThrows
	public QuicHalfOpen(InetAddress host, ChannelHandler connectionHandler) {
		final SelfSignedCertificate certificate = new SelfSignedCertificate();

		server = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(new QuicServerCodecBuilder()
			.sslContext(QuicSslContextBuilder.forServer(certificate.privateKey(), null, certificate.certificate())
				.applicationProtocols(PROTOCOL).build())
			.tokenHandler(InsecureQuicTokenHandler.INSTANCE)
			.initialMaxData(Network.MAX_WINDOW_SIZE)
			.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
			.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES)
			.handler(new ChannelInitializer<QuicChannel>() {
				@Override
				protected void initChannel(QuicChannel connection) {
					connection.pipeline().addLast(new Beginning(), connectionHandler);
				}
			}).streamHandler(new ChannelInboundHandlerAdapter()).build()).bind(host, 0).sync().channel();

		client = new Bootstrap().group(group).channel(NioDatagramChannel.class)
			.handler(new ChannelInitializer<Channel>() {
				@Override
				protected void initChannel(Channel datagrams) {
					datagrams.pipeline().addLast(new QuicClientCodecBuilder()
						.sslContext(QuicSslContextBuilder.forClient()
							.trustManager(InsecureTrustManagerFactory.INSTANCE).applicationProtocols(PROTOCOL).build())
						.initialMaxData(Network.MAX_WINDOW_SIZE)
						.initialMaxStreamDataBidirectionalLocal(Network.MAX_WINDOW_SIZE)
						.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
						.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES).build(), new Silence());
				}
			}).bind(host, 0).sync().channel();

		QuicChannel.newBootstrap(client).handler(new ChannelInboundHandlerAdapter())
			.streamHandler(new ChannelInboundHandlerAdapter()).remoteAddress(server.localAddress()).connect();
	}

	/* The server's end of the connection, once it has begun and the handler given has seen where it comes from */
	@SneakyThrows
	public QuicChannel serverEnd() {
		return begun.get(WAIT_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	public void close() throws InterruptedException {
		client.close().sync();
		server.close().sync();
		group.shutdownGracefully(0, WAIT_SECONDS, TimeUnit.SECONDS).sync();
	}
}
