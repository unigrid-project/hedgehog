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

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

import java.util.Optional;

@Sharable
public class PublishSporkChannelHandler
        extends AbstractInboundHandler<PublishSpork> {

    private static final Logger log =
            LoggerFactory.getLogger(PublishSporkChannelHandler.class);

    public PublishSporkChannelHandler() {
        super(PublishSpork.class);
    }

    @Override
    public void typedChannelRead(ChannelHandlerContext ctx,
                                 PublishSpork publishSpork) {

        CDIUtil.resolveAndRun(SporkDatabase.class, db -> {

            GridSpork newSpork = publishSpork.getGridSpork();

            if (newSpork == null) {
                log.error("Received null spork");
                return;
            }

            GridSpork oldSpork =
                    db.get(newSpork.getType());

            if (newSpork.isNewerThan(oldSpork)
                    && newSpork.isValidSignature()) {

                db.set(newSpork);

                CDIUtil.resolveAndRun(Topology.class, topology ->
                        Topology.sendAll(
                                publishSpork,
                                topology,
                                Optional.empty()
                        )
                );
            }
        });
    }
}