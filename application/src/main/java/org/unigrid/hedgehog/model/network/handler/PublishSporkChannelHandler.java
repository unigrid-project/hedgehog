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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.Dialed;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkIntake;
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

		final Account account = accountOf(ctx.channel());

		CDIUtil.resolveAndRun(SporkDatabase.class, db -> {
			CDIUtil.resolveAndRun(PendingSporks.class, pendingSporks -> {
				CDIUtil.resolveAndRun(SporkLag.class, lag -> {
					if (receive(spork, db, pendingSporks, lag, account.source(), account.pool())) {
						CDIUtil.resolveAndRun(Topology.class, topology -> {
							SporkRecipients.sendAll(spork, topology);
						});
					}
				});
			});
		});
	}

	/* What the checks of the sporks of a stream are paid from */
	record Account(String source, SporkLag.Pool pool) {
	}

	/* A peer that this node dialed pays from the pool of those, by its network group, so that the addresses of one
	   network draw one share however many of them the node dialed, while a peer that an operator configured has a share
	   of its own. Every other peer pays from the pool of the rest, by its network group too. */
	static Account accountOf(Channel stream) {
		return Dialed.of(stream).map(dialed -> new Account(dialed == Dialed.CONFIGURED
			? RemoteAddress.idOf(stream) : RemoteAddress.groupOf(stream), SporkLag.Pool.TRUSTED))
			.orElseGet(() -> new Account(RemoteAddress.groupOf(stream), SporkLag.Pool.OTHER));
	}

	/**
	* Stores a co-signed spork that may replace the stored one, or holds a spork signed once as a proposal.
	* Returns whether the spork was new to this node, and should therefore travel further. The source is the peer
	* that sent it, as {@link RemoteAddress#idOf} names it, which pays as a peer that this node did not dial.
	*
	* <p>Anyone can send a frame and checking a signature is the costly part, so each signature of a spork is checked
	* on the account of its source ({@link SporkLag.Turn}), as it is checked. A peer that floods forged sporks spends
	* its own checks and no other peer's, and a genuine co-signed spork gives back what it cost for the next one,
	* while a proposal gives back nothing, as one key alone makes as many as it likes. What the
	* node does once it took a co-signed spork, storing it and dropping the proposals it outdates, is its own work and
	* is paid for by no one, so a source whose checks run out right then leaves the node whole.
	*/
	public boolean receive(GridSpork spork, SporkDatabase db, PendingSporks pendingSporks, SporkLag lag,
		String source) {

		return receive(spork, db, pendingSporks, lag, source, SporkLag.Pool.OTHER);
	}

	/**
	* Stores or holds a spork as {@link #receive(GridSpork, SporkDatabase, PendingSporks, SporkLag, String)} does, on
	* the account of a source in the pool given: a peer that this node dialed, or any other ({@link SporkLag.Pool}).
	*/
	public boolean receive(GridSpork spork, SporkDatabase db, PendingSporks pendingSporks, SporkLag lag,
		String source, SporkLag.Pool pool) {

		final GridSpork stored = db.get(spork.getType());
		final SporkLag.Turn turn = lag.turnOf(spork.getType(), source, pool);

		/* Before any decision, so a spork this node turns down still tells it the network has moved on */
		lag.record(spork, db, turn);

		/* One active key makes as many genuine proposals as it likes, so what one cost is never given back */
		if (spork.isPending()) {
			return turn.charging(() -> pendingSporks.offer(spork, () -> db.get(spork.getType()), turn));
		}

		return SporkIntake.take(spork, stored, db, pendingSporks, turn) == SporkIntake.Outcome.STORED;
	}
}
