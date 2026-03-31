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
 package org.unigrid.hedgehog.model.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import org.unigrid.hedgehog.model.network.packet.Packet;

import java.util.Optional;

public class ConnectionContainer implements Connection {

    protected Optional<EventLoopGroup> group = Optional.empty();
    protected Channel channel;

    public ConnectionContainer(Channel channel) {
        this.channel = channel;
    }

    public ConnectionContainer(Channel channel, EventLoopGroup group) {
        this.channel = channel;
        this.group = Optional.ofNullable(group);
    }

    @Override
    public Channel getChannel() {
        return channel;
    }

    @Override
    public ChannelFuture send(Packet packet) {
        if (channel != null && channel.isActive()) {
            return channel.writeAndFlush(packet);
        }
        throw new IllegalStateException("Channel is not active");
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close();
        }

        group.ifPresent(EventLoopGroup::shutdownGracefully);
    }

    @Override
    public void closeDirty() {
        if (channel != null) {
            channel.close();
        }

        group.ifPresent(EventLoopGroup::shutdownGracefully);
    }
} 