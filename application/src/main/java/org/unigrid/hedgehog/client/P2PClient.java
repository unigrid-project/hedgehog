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
 package org.unigrid.hedgehog.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.ConnectionContainer;
import org.unigrid.hedgehog.model.network.schedule.AbstractSchedule;
import org.unigrid.hedgehog.model.network.schedule.PingSchedule;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class P2PClient extends ConnectionContainer {

    private final String hostname;
    private final int port;

    public P2PClient(String hostname, int port) {
        super((Channel) null);
        this.hostname = hostname;
        this.port = port;
    }

    public void connect() throws InterruptedException {
        InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);

        group = Optional.of(new NioEventLoopGroup(Network.COMMUNICATION_THREADS));

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group.get())
                 .channel(NioSocketChannel.class)
                 .handler(new ChannelInitializer<Channel>() {
                     @Override
                     protected void initChannel(Channel ch) {
                         handlers().forEach(ch.pipeline()::addLast);
                     }
                 });

        channel = bootstrap.connect(new InetSocketAddress(hostname, port))
                           .sync()
                           .channel();

        schedules().forEach(schedule ->
                schedule.start(channel.eventLoop(), channel)
        );
    }

    private List<ChannelHandler> handlers() {
        return Arrays.asList(new LoggingHandler(LogLevel.DEBUG));
    }

    private List<AbstractSchedule> schedules() {
        return Arrays.asList(new PingSchedule());
    }

    public void shutdownClient() {
        super.close();
    }
}