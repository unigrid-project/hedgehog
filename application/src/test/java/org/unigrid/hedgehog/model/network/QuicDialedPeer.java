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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.model.Network;

/* A QUIC server that a node dials and that offers the protocols it is given, as a peer would. It reads what the node
   sends and answers nothing of its own, and keeps the connections that are made to it and the streams that are opened
   on them, in the order they came, so that a test can end one as a peer does and tell when the node dials again. */
public final class QuicDialedPeer implements AutoCloseable {
	private static final int WAIT_SECONDS = 5;

	private final EventLoopGroup group = new NioEventLoopGroup(1);
	private final List<QuicChannel> connections = new CopyOnWriteArrayList<>();
	private final List<QuicStreamChannel> streams = new CopyOnWriteArrayList<>();
	private final Channel socket;

	/* QUIC creates a stream at the peer with its first bytes, so a stream is here once the node wrote to it */
	private final class Opened extends ChannelInitializer<QuicStreamChannel> {
		@Override
		protected void initChannel(final QuicStreamChannel stream) {
			stream.pipeline().addLast(new ChannelInboundHandlerAdapter() {
				@Override public void channelRead(final ChannelHandlerContext ctx, final Object message) {
					ReferenceCountUtil.release(message);
				}
			});

			streams.add(stream);
		}
	}

	@SneakyThrows
	private QuicDialedPeer(final String... protocols) {
		final SelfSignedCertificate certificate = new SelfSignedCertificate();

		try {
			socket = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(new QuicServerCodecBuilder()
				.sslContext(QuicSslContextBuilder.forServer(certificate.privateKey(), null, certificate.certificate())
					.applicationProtocols(protocols).build())
				.tokenHandler(InsecureQuicTokenHandler.INSTANCE)
				.initialMaxData(Network.MAX_WINDOW_SIZE)
				.initialMaxStreamDataBidirectionalLocal(Network.MAX_WINDOW_SIZE)
				.initialMaxStreamDataBidirectionalRemote(Network.MAX_WINDOW_SIZE)
				.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
				.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES)
				.handler(new ChannelInitializer<QuicChannel>() {
					@Override protected void initChannel(final QuicChannel connection) {
						connections.add(connection);
					}
				}).streamHandler(new Opened()).build()).bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
		} catch (Exception ex) {
			group.shutdownGracefully(0, WAIT_SECONDS, TimeUnit.SECONDS);
			throw ex;
		} finally {
			certificate.delete();
		}
	}

	public static QuicDialedPeer offering(final String... protocols) {
		return new QuicDialedPeer(protocols);
	}

	public InetSocketAddress address() {
		return (InetSocketAddress) socket.localAddress();
	}

	/* The node this peer is to a topology */
	public Node node() {
		return Node.builder().address(address()).build();
	}

	public List<QuicChannel> connections() {
		return List.copyOf(connections);
	}

	public List<QuicStreamChannel> streams() {
		return List.copyOf(streams);
	}

	@Override
	public void close() throws InterruptedException {
		socket.close().sync();
		group.shutdownGracefully(0, WAIT_SECONDS, TimeUnit.SECONDS).sync();
	}
}
