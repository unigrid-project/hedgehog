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

package org.unigrid.hedgehog.client.p2p;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.ConnectionContainer;
import org.unigrid.hedgehog.model.network.Dialed;
import org.unigrid.hedgehog.model.network.PeerProtocol;
import org.unigrid.hedgehog.transport.initializer.ChainStreamInitializer;
import org.unigrid.hedgehog.transport.initializer.PeerPipeline;
import org.unigrid.hedgehog.transport.handler.ProtocolMismatchHandler;
import org.unigrid.hedgehog.transport.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.transport.schedule.Schedulable;

@Slf4j
public class P2PClient extends ConnectionContainer {
	public P2PClient(String hostname, int port) throws ExecutionException, InterruptedException, TimeoutException {
		this(hostname, port, Dialed.CHOSEN);
	}

	/* The connection is marked with why this node dialed the peer, which decides what the checks of what it sends are
	   paid from */
	public P2PClient(String hostname, int port, Dialed dialed)
		throws ExecutionException, InterruptedException, TimeoutException {

		super();
		InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
		/* A client has one socket, which one thread serves, and a node holds a client for each peer it reaches */
		group = Optional.of(new NioEventLoopGroup(1));

		try {
			channel = connect(group.get(), hostname, port, dialed);
		} catch (Exception ex) {
			group.get().shutdownGracefully();
			throw ex;
		}
	}

	private static QuicStreamChannel connect(EventLoopGroup group, String hostname, int port,
		Dialed dialed) throws ExecutionException, InterruptedException, TimeoutException {

		final String[] protocols = Network.getProtocols();
		final boolean ledger = Network.hasLedger(protocols);
		final boolean preview = Network.hasPreview(protocols);
		final QuicSslContext context = QuicSslContextBuilder.forClient()
			.trustManager(InsecureTrustManagerFactory.INSTANCE)
			.applicationProtocols(protocols)
			.build();

		final ChannelHandler codec = new QuicClientCodecBuilder()
			.initialMaxData(Network.MAX_WINDOW_SIZE)
			.initialMaxStreamDataBidirectionalLocal(Network.MAX_WINDOW_SIZE)
			.initialMaxStreamDataBidirectionalRemote(Network.MAX_WINDOW_SIZE)
			.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
			.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES)
			.sslContext(context)
			.build();

		final Channel channelBootstrap = new Bootstrap().group(group)
			.channel(NioDatagramChannel.class)
			.handler(codec)
			.bind(0).sync().channel();

		final InetSocketAddress address = new InetSocketAddress(hostname, port);
		final QuicChannel quicChannel = QuicChannel.newBootstrap(channelBootstrap)
			.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY, address)
			.attr(Dialed.KEY, dialed)
			.handler(new ProtocolMismatchHandler())
			.streamHandler(new ChannelInboundHandlerAdapter())
			.remoteAddress(address)
			.connect().get(Network.CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

		// We create new stream so we can support bidirectional communication (in case we expect a response)
		// TODO: Add support for ChannelCollector
		final QuicStreamChannel main = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
			new RegisterQuicChannelInitializer(() -> handlers(ledger, preview),
				() -> schedulers(ledger, preview), RegisterQuicChannelInitializer.Type.CLIENT)
		).sync().getNow();

		if (PeerProtocol.speaksProxy(quicChannel)) {
			openChainStream(quicChannel);
		}

		return main;
	}

	/* The main stream carries everything else, so a connection that cannot open a stream for chain traffic is kept.
	   QUIC fails the opening with a checked exception, for the stream limit of the peer as for a connection that is
	   gone, so the failure is read off the future instead of caught. */
	private static void openChainStream(final QuicChannel quicChannel) throws InterruptedException {
		final Future<QuicStreamChannel> opened = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
			new ChainStreamInitializer(RegisterQuicChannelInitializer.Type.CLIENT)).await();

		if (!opened.isSuccess()) {
			log.warn("Could not open the chain stream: {}", opened.cause().getClass().getName());
		}
	}

	static List<ChannelHandler> handlers(final boolean ledger, final boolean preview) {
		return PeerPipeline.handlers(RegisterQuicChannelInitializer.Type.CLIENT, ledger, preview);
	}

	static List<Schedulable> schedulers(final boolean ledger, final boolean preview) {
		return PeerPipeline.schedulers(ledger, preview);
	}
}
