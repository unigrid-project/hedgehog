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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@Slf4j
@Sharable
public class PublishSporkChannelHandler extends AbstractInboundHandler<PublishSpork> {
	public PublishSporkChannelHandler() {
		super(PublishSpork.class);
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, PublishSpork publishSpork) throws Exception {
		final GridSpork spork = publishSpork.getGridSpork();

		if (spork.getType() == Type.UNDEFINED) {
			log.atError().log("Received unsupported spork type - ignoring.");
			return;
		}

		CDIUtil.resolveAndRun(SporkDatabase.class, db -> {
			CDIUtil.resolveAndRun(PendingSporks.class, pendingSporks -> {
				if (receive(spork, db, pendingSporks)) {
					CDIUtil.resolveAndRun(Topology.class, topology -> {
						// TODO: Handle errors better rather than sending Optional.empty()
						Topology.sendAll(publishSpork, topology, Optional.empty());
					});
				}
			});
		});
	}

	/**
	* Stores a co-signed spork that may replace the stored one, or holds a spork signed once as a proposal.
	* Returns whether the spork was new to this node, and should therefore travel further.
	*/
	public boolean receive(GridSpork spork, SporkDatabase db, PendingSporks pendingSporks) {
		final GridSpork stored = db.get(spork.getType());

		if (spork.isPending()) {
			return pendingSporks.offer(spork, stored);
		}

		if (spork.canReplace(stored)) {
			db.set(spork);
			pendingSporks.retainProposalsOver(spork);
			return true;
		}

		log.atDebug().log("Dropped a {} spork that cannot replace the stored one", spork.getType());
		return false;
	}
}
