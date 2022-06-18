/*
    Unigrid Hedgehog 
    Copyright © 2021-2022 The Unigrid Foundation

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
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.model.network.handler.BasicChannelHandler;
import org.unigrid.hedgehog.model.network.handler.RegisterQuicHandler;
import org.unigrid.hedgehog.model.network.handler.EncryptedTokenHandler;
import org.unigrid.hedgehog.model.network.codec.PublishSporkDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkEncoder;
import org.unigrid.hedgehog.server.Server;

@Eager @ApplicationScoped
public class P2PServer extends Server {
	private NioEventLoopGroup group;
	private Channel p2p;

	@Inject
	private EncryptedTokenHandler encryptedTokenHandler;

	@PostConstruct @SneakyThrows
	private void init() {
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
			.streamHandler(new RegisterQuicHandler(
				new PublishSporkEncoder(),
				new PublishSporkDecoder(),
				new BasicChannelHandler()
			)).build();

		final Bootstrap bootstrap = new Bootstrap();
		group = new NioEventLoopGroup(1);

		p2p = bootstrap.group(group)
			.channel(NioDatagramChannel.class)
			.handler(codec)
			.bind(NetOptions.getHost(), NetOptions.getPort())
			.sync().channel();
	}

	@PreDestroy
	private void destroy() {
		p2p.close();
		group.shutdownGracefully();
	}
}
