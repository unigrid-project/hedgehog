/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;
import org.unigrid.hedgehog.model.network.handler.PingChannelHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.BiConsumer;
import lombok.Getter;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.PingDecoder;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.handler.GenericChannelInitializer;
import org.unigrid.hedgehog.model.network.codec.PublishSporkDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkEncoder;
import org.unigrid.hedgehog.model.network.packet.Packet;

public class P2PClient {
	private final NioEventLoopGroup group = new NioEventLoopGroup(Network.COMMUNICATION_THREADS);
	@Getter private final QuicStreamChannel quicStreamChannel;

	public P2PClient(String hostname, int port)
		throws ExecutionException, InterruptedException, CertificateException, NoSuchAlgorithmException {

		InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);

		final QuicSslContext context = QuicSslContextBuilder.forClient()
			.trustManager(InsecureTrustManagerFactory.INSTANCE)
			.applicationProtocols(Network.PROTOCOLS)
			.build();

		final ChannelHandler codec = new QuicClientCodecBuilder()
			.initialMaxData(Network.MAX_DATA_SIZE)
			.initialMaxStreamDataBidirectionalLocal(Network.MAX_DATA_SIZE)
			.initialMaxStreamDataBidirectionalRemote(Network.MAX_DATA_SIZE)
			.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
			.sslContext(context)
			.build();

		final Channel channel = new Bootstrap().group(group)
			.channel(NioDatagramChannel.class)
			.handler(codec)
			.bind(0).sync().channel();

		final QuicChannel quicChannel = QuicChannel.newBootstrap(channel)
			.streamHandler(new ChannelInboundHandlerAdapter())
			.remoteAddress(new InetSocketAddress(hostname, port))
			.connect()
			.get();

		// We create new stream so we can support bidirectional communication (in case we expect a response)
		quicStreamChannel = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
			new GenericChannelInitializer(() -> {
				return Arrays.asList(new LoggingHandler(LogLevel.DEBUG),
					new FrameDecoder(),
					new PingEncoder(), new PingDecoder(),
					new PublishSporkEncoder(), new PublishSporkDecoder(),
					new PingChannelHandler()
				);
			}, GenericChannelInitializer.Type.CLIENT)).sync().getNow();
	}

	public ChannelFuture send(Packet packet) {
		return quicStreamChannel.writeAndFlush(packet);
	}

	@SneakyThrows
	public void close() {
		if (!quicStreamChannel.isShutdown()) {
			quicStreamChannel.shutdown().sync();
		}

		if (!group.isShuttingDown()) {
			group.shutdownGracefully().sync();
		}
	}

	@SneakyThrows
	public void closeDirty() {
		if (!quicStreamChannel.isShutdown()) {
			quicStreamChannel.shutdown();
		}

		if (!group.isShuttingDown()) {
			group.shutdownGracefully();
		}
	}

	public static void send(Packet packet, Node node, Optional<BiConsumer<Node, Future>> consumer) {
		if (node.getChannel().isPresent()) {
			final ChannelFuture out = node.getChannel().get().writeAndFlush(packet);

			out.addListener(f -> {
				consumer.ifPresent(c -> {
					c.accept(node, f);
				});
			});
		}
	}

	public static void sendAll(Packet packet, Topology topology, Optional<BiConsumer<Node, Future>> consumer) {
		for (Node node : topology.getNodes().values()) {
			node.getChannel().ifPresent(channel -> {
				send(packet, node, consumer);
			});
		}
	}
}
