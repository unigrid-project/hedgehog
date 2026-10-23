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
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.BoardKeysAuthority;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;

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
			log.atDebug().log("Received unsupported spork type - ignoring.");
			return;
		}

		final String source = RemoteAddress.idOf(ctx.channel());

		CDIUtil.resolveAndRun(SporkDatabase.class, db -> {
			CDIUtil.resolveAndRun(PendingSporks.class, pendingSporks -> {
				CDIUtil.resolveAndRun(SporkLag.class, lag -> {
					if (receive(spork, db, pendingSporks, lag, source)) {
						CDIUtil.resolveAndRun(Topology.class, topology -> {
							SporkRecipients.sendAll(spork, topology);
						});
					}
				});
			});
		});
	}

	/**
	* Stores a co-signed spork that may replace the stored one, or holds a spork signed once as a proposal.
	* Returns whether the spork was new to this node, and should therefore travel further. The source is the peer
	* that sent it, as {@link RemoteAddress#idOf} names it.
	*
	* <p>Anyone can send a frame and checking a signature is the costly part, so the signatures of a spork are
	* checked only on a turn of its source ({@link SporkLag.Turn}). A peer that floods forged sporks spends its own
	* turns and no other peer's, and a genuine spork gives its turn back for the next one.
	*/
	public boolean receive(GridSpork spork, SporkDatabase db, PendingSporks pendingSporks, SporkLag lag,
		String source) {

		final GridSpork stored = db.get(spork.getType());
		final SporkLag.Turn turn = lag.turnOf(spork, source);

		/* Before any decision, so a spork this node turns down still tells it the network has moved on */
		lag.record(spork, db, turn);

		if (isTaken(spork, stored, db, pendingSporks, turn)) {
			turn.giveBack();
			return true;
		}

		return false;
	}

	private static boolean isTaken(GridSpork spork, GridSpork stored, SporkDatabase db, PendingSporks pendingSporks,
		SporkLag.Turn turn) {

		if (spork.isPending()) {
			return pendingSporks.offer(spork, stored, turn);
		}

		if (pendingSporks.isAheadOfTime(spork)) {
			log.atDebug().log("Dropped a {} spork stamped ahead of the clock", spork.getType());
			return false;
		}

		if (spork instanceof BoardKeys boardKeys) {
			return BoardKeysAuthority.accept(boardKeys, db, pendingSporks, turn);
		}

		if (spork.canReplace(stored, turn)) {
			db.set(spork);
			pendingSporks.retainProposalsOver(spork);
			return true;
		}

		log.atDebug().log("Dropped a {} spork that cannot replace the stored one", spork.getType());
		return false;
	}
}
