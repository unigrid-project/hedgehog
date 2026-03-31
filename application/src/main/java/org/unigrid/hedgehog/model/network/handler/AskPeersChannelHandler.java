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

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.model.network.packet.AskPeers;
import org.unigrid.hedgehog.model.network.packet.Peers;

@Slf4j
@Sharable
public class AskPeersChannelHandler extends AbstractInboundHandler<AskPeers> {

    // Skapa en ny logger för den här klassen
    private static final Logger log = LoggerFactory.getLogger(AskPeersChannelHandler.class);

    public AskPeersChannelHandler() {
        super(AskPeers.class);
    }

    @Override
    protected void typedChannelRead(ChannelHandlerContext ctx, AskPeers packet) {
        log.debug("Received AskPeers request: amount={}", packet.getAmount());

        Peers peers = new Peers();
        peers.setAmount(packet.getAmount());

        ctx.writeAndFlush(peers)
           .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }
}
