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
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.collection.NullableMap;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import static org.unigrid.hedgehog.model.spork.GridSpork.Type.*;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@Slf4j
@Sharable
public class PublishSporkChannelHandler extends AbstractInboundHandler<PublishSpork> {
	public PublishSporkChannelHandler() {
		super(PublishSpork.class);
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, PublishSpork publishSpork) throws Exception {
		CDIUtil.resolveAndRun(SporkDatabase.class, db -> {
			final GridSpork newSpork = publishSpork.getGridSpork();

			final Map<Type, GridSpork> entries = NullableMap.of(
				MINT_STORAGE, db.getMintStorage(),
				MINT_SUPPLY, db.getMintSupply(),
				VESTING_STORAGE, db.getVestingStorage(),
				STATISTICS_PUBKEY, db.getStatisticsPubKey(),
				COSMOS, db.getCosmos()
			);

			final GridSpork oldSpork = entries.get(newSpork.getType());

			if (!entries.containsKey(newSpork.getType())) {
				log.atError().log("Received unsupported spork type - ignoring.");
				return;
				/* Bail out on unsupported type */
			}

			if (newSpork.isNewerThan(oldSpork) && newSpork.isValidSignature()) {
				db.set(newSpork);

				CDIUtil.resolveAndRun(Topology.class, topology -> {
					// TODO: Handle errors better rather than sending Optional.empty()
					Topology.sendAll(publishSpork, topology, Optional.empty());
				});
			}
		});
	}
}
