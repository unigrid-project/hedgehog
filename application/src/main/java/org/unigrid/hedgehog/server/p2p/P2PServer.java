/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.PingDecoder;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkEncoder;
import org.unigrid.hedgehog.model.network.handler.EncryptedTokenHandler;
import org.unigrid.hedgehog.model.network.handler.PingChannelHandler;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.schedule.PingSchedule;
import org.unigrid.hedgehog.server.AbstractServer;

@Eager @ApplicationScoped
public class P2PServer extends AbstractServer {
	private final NioEventLoopGroup group = new NioEventLoopGroup(Network.COMMUNICATION_THREADS);
	private Channel channel;

	@Inject
	private EncryptedTokenHandler encryptedTokenHandler;

	@PostConstruct @SneakyThrows
	private void init() {
		InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);

		final SelfSignedCertificate certificate = new SelfSignedCertificate();
		final QuicSslContext context = QuicSslContextBuilder.forServer(
			certificate.privateKey(), null, certificate.certificate())
			.applicationProtocols(Network.PROTOCOLS).build();

		final ChannelHandler codec = new QuicServerCodecBuilder()
			.sslContext(context)
			.tokenHandler(encryptedTokenHandler)
			.initialMaxData(Network.MAX_DATA_SIZE)
			.initialMaxStreamDataBidirectionalLocal(Network.MAX_DATA_SIZE)
			.initialMaxStreamDataBidirectionalRemote(Network.MAX_DATA_SIZE)
			.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
			.streamHandler(new RegisterQuicChannelInitializer(() -> {
				return Arrays.asList(new LoggingHandler(LogLevel.DEBUG),
					new FrameDecoder(),
					new PingEncoder(), new PingDecoder(),
					new PublishSporkEncoder(), new PublishSporkDecoder(),
					new PingChannelHandler(), new PublishSporkChannelHandler()
				);
			}, () -> {
				return Arrays.asList(
					new PingSchedule()
				);
			}, RegisterQuicChannelInitializer.Type.SERVER)).build();

		channel = new Bootstrap().group(group)
			.channel(NioDatagramChannel.class)
			.handler(codec)
			.bind(NetOptions.getHost(), NetOptions.getPort())
			.sync().channel();
	}

	@Override
	public Channel getChannel() {
		return channel;
	}

	@PreDestroy
	private void destroy() {
		channel.close();
		group.shutdownGracefully();
	}
}
