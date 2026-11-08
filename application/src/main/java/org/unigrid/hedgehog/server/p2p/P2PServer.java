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

package org.unigrid.hedgehog.server.p2p;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.ConnectionLimiter;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.transport.initializer.ChainStreamInitializer;
import org.unigrid.hedgehog.transport.initializer.PeerPipeline;
import org.unigrid.hedgehog.transport.initializer.StreamRouter;
import org.unigrid.hedgehog.transport.handler.ConnectionHandler;
import org.unigrid.hedgehog.transport.handler.EncryptedTokenHandler;
import org.unigrid.hedgehog.transport.handler.ProtocolMismatchHandler;
import org.unigrid.hedgehog.transport.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.transport.schedule.Schedulable;
import org.unigrid.hedgehog.server.AbstractServer;

@Slf4j
@Eager @ApplicationScoped
public class P2PServer extends AbstractServer {
	private static final long CLOSE_WAIT_MILLIS = 2_000;

	private final NioEventLoopGroup group = new NioEventLoopGroup(Network.COMMUNICATION_THREADS);
	private final ConnectionLimiter limiter = new ConnectionLimiter();
	private final ChannelGroup connections = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
	private TopologyThread topologyThread;
	@Getter(onMethod_ = @Override) private Channel channel;

	@Inject
	private EncryptedTokenHandler encryptedTokenHandler;

	@Inject
	private Topology topology;

	@PostConstruct @SneakyThrows
	private void init() {
		InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
		log.atDebug().log("Init P2P server");
		final SelfSignedCertificate certificate = new SelfSignedCertificate();
		final String[] protocols = Network.getProtocols();
		final boolean ledger = Network.hasLedger(protocols);
		final boolean preview = Network.hasPreview(protocols);
		final QuicSslContext context = QuicSslContextBuilder.forServer(
			certificate.privateKey(), null, certificate.certificate())
			.applicationProtocols(protocols).build();
		log.atDebug().log("adding channels");
		// TODO: Add support for ChannelCollector
		final ChannelHandler codec = new QuicServerCodecBuilder()
			.sslContext(context)
			.tokenHandler(encryptedTokenHandler)
			.initialMaxData(Network.MAX_WINDOW_SIZE)
			.initialMaxStreamDataBidirectionalLocal(Network.MAX_WINDOW_SIZE)
			.initialMaxStreamDataBidirectionalRemote(Network.MAX_WINDOW_SIZE)
			.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
			.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES)
			.handler(new ChannelInitializer<QuicChannel>() {
				@Override
				protected void initChannel(QuicChannel quicChannel) {
					connections.add(quicChannel);
					quicChannel.pipeline().addLast(new ProtocolMismatchHandler());
					quicChannel.pipeline().addLast(new ConnectionHandler(limiter));
				}
			})
			.streamHandler(new StreamRouter(new RegisterQuicChannelInitializer(() -> handlers(ledger, preview),
				() -> schedulers(ledger, preview), RegisterQuicChannelInitializer.Type.SERVER),
				new ChainStreamInitializer(RegisterQuicChannelInitializer.Type.SERVER))).build();

		channel = new Bootstrap().group(group)
			.channel(NioDatagramChannel.class)
			.handler(codec)
			.bind(NetOptions.getHost(), NetOptions.getPort())
			.sync().channel();
		log.atDebug().log("Init topology");
		topologyThread = new TopologyThread();
		topologyThread.start();
	}

	static List<ChannelHandler> handlers(final boolean ledger, final boolean preview) {
		return PeerPipeline.handlers(RegisterQuicChannelInitializer.Type.SERVER, ledger, preview);
	}

	static List<Schedulable> schedulers(final boolean ledger, final boolean preview) {
		return PeerPipeline.schedulers(ledger, preview);
	}

	/* A peer drops a connection only once it is closed, and holds one that is not until it idles out, minutes later, so
	   every connection is closed with the node, each way, before its socket: the peer then dials the node again once it
	   is back. A peer that does not answer holds up the stop for no longer than the wait. */
	@PreDestroy
	void destroy() {
		topologyThread.exit();
		topology.forEach(node -> node.getConnection().map(Connection::getChannel).map(Channel::parent)
			.ifPresent(connections::add));
		connections.close().awaitUninterruptibly(CLOSE_WAIT_MILLIS);
		topology.forEach(node -> node.getConnection().ifPresent(Connection::closeDirty));
		channel.close();
		group.shutdownGracefully();
	}
}
