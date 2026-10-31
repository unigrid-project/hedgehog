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
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.ConnectionLimiter;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.initializer.ChainStreamInitializer;
import org.unigrid.hedgehog.model.network.initializer.LedgerPipeline;
import org.unigrid.hedgehog.model.network.initializer.StoragePipeline;
import org.unigrid.hedgehog.model.network.initializer.StreamRouter;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.GridnodeDecoder;
import org.unigrid.hedgehog.model.network.codec.GridnodeEncoder;
import org.unigrid.hedgehog.model.network.codec.HelloDecoder;
import org.unigrid.hedgehog.model.network.codec.PingDecoder;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.codec.PublishPeersDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishPeersEncoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkEncoder;
import org.unigrid.hedgehog.model.network.handler.ConnectionHandler;
import org.unigrid.hedgehog.model.network.handler.EncryptedTokenHandler;
import org.unigrid.hedgehog.model.network.handler.PeerActivityHandler;
import org.unigrid.hedgehog.model.network.handler.PublishGridnodeChannelHandler;
import org.unigrid.hedgehog.model.network.handler.HelloChannelHandler;
import org.unigrid.hedgehog.model.network.handler.PingChannelHandler;
import org.unigrid.hedgehog.model.network.handler.ProtocolMismatchHandler;
import org.unigrid.hedgehog.model.network.handler.PublishPeersChannelHandler;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.schedule.LedgerStatusSchedule;
import org.unigrid.hedgehog.model.network.schedule.PingSchedule;
import org.unigrid.hedgehog.model.network.schedule.PublishSporkSchedule;
import org.unigrid.hedgehog.model.network.schedule.PublishGridnodeSchedule;
import org.unigrid.hedgehog.model.network.schedule.PublishPeersSchedule;
import org.unigrid.hedgehog.model.network.schedule.Schedulable;
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
			.streamHandler(new StreamRouter(new RegisterQuicChannelInitializer(() -> handlers(ledger),
				() -> schedulers(ledger), RegisterQuicChannelInitializer.Type.SERVER),
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

	/* The ledger stages, and the schedule that announces streams to the ledger, are there only when the node offers the
	   ledger protocol, as a node without it, such as one of an earlier release, would not know their packets */
	static List<ChannelHandler> handlers(final boolean ledger) {
		final List<ChannelHandler> handlers = new ArrayList<>(Arrays.asList(
			new PeerActivityHandler(),
			new LoggingHandler(LogLevel.DEBUG),
			new FrameDecoder(),
			new HelloDecoder(),
			new GridnodeEncoder(), new GridnodeDecoder(),
			new PingEncoder(), new PingDecoder(),
			new PublishSporkEncoder(), new PublishSporkDecoder(),
			new PublishPeersEncoder(), new PublishPeersDecoder(),
			new PingChannelHandler(), new PublishSporkChannelHandler(),
			new HelloChannelHandler(), new PublishPeersChannelHandler(),
			new PublishGridnodeChannelHandler()
		));

		handlers.addAll(StoragePipeline.handlers());

		if (ledger) {
			handlers.addAll(LedgerPipeline.handlers());
		}

		return handlers;
	}

	static List<Schedulable> schedulers(final boolean ledger) {
		final List<Schedulable> schedulers = new ArrayList<>(Arrays.asList(
			new PingSchedule(),
			new PublishPeersSchedule(),
			new PublishSporkSchedule(),
			new PublishGridnodeSchedule()
		));

		if (ledger) {
			schedulers.add(new LedgerStatusSchedule());
		}

		return schedulers;
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
