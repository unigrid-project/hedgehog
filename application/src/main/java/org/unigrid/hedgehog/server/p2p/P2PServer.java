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
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.TopologyThread;
import org.unigrid.hedgehog.model.network.handler.ConnectionHandler;
import org.unigrid.hedgehog.model.network.handler.EncryptedTokenHandler;
import org.unigrid.hedgehog.server.AbstractServer;
import org.unigrid.hedgehog.command.option.NetOptions;

import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class P2PServer extends AbstractServer {

    private final NioEventLoopGroup group = new NioEventLoopGroup(Network.COMMUNICATION_THREADS);
    private TopologyThread topologyThread;
    private Channel channel;

    private final EncryptedTokenHandler encryptedTokenHandler = new EncryptedTokenHandler();

    @PostConstruct
    public void init() throws Exception {
        start(NetOptions.getHost(), NetOptions.getPort());
    }

    public void start(String host, int port) throws Exception {
        final SelfSignedCertificate certificate = new SelfSignedCertificate();
        final QuicSslContext context = QuicSslContextBuilder.forServer(
                certificate.privateKey(), null, certificate.certificate())
                .applicationProtocols(Network.getProtocols())
                .build();

        final ChannelHandler codec = new QuicServerCodecBuilder()
                .sslContext(context)
                .tokenHandler(encryptedTokenHandler)
                .initialMaxData(Network.MAX_DATA_SIZE)
                .initialMaxStreamDataBidirectionalLocal(Network.MAX_DATA_SIZE)
                .initialMaxStreamDataBidirectionalRemote(Network.MAX_DATA_SIZE)
                .initialMaxStreamsBidirectional(Network.MAX_STREAMS)
                .maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES)
                .handler(new ConnectionHandler())
                .build();

        channel = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(codec)
                .bind(host, port)
                .sync()
                .channel();

        topologyThread = new TopologyThread();
        topologyThread.start();
    }

    @Override
    public Channel getChannel() {
        return channel;
    }

    @PreDestroy
    public void destroy() {
        if (topologyThread != null) topologyThread.exit();
        if (channel != null) channel.close();
        group.shutdownGracefully();
    }
}