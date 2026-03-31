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
 package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.model.network.packet.Packet;

public abstract class AbstractInboundHandler<T extends Packet>
        extends ChannelInboundHandlerAdapter {

    private static final Logger log =
            LoggerFactory.getLogger(AbstractInboundHandler.class);

    private final Class<T> clazz;

    protected AbstractInboundHandler(Class<T> clazz) {
        this.clazz = clazz;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (clazz.isInstance(msg)) {
            typedChannelRead(ctx, clazz.cast(msg));
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Inbound handler exception: {}", cause.getMessage());
        log.debug("Stacktrace:\n{}", ExceptionUtils.getStackTrace(cause));
        ctx.close();
    }

    protected abstract void typedChannelRead(ChannelHandlerContext ctx, T packet) throws Exception;
}
