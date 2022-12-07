/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.client.P2PClient;
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
		final Instance<SporkDatabase> db = CDI.current().select(SporkDatabase.class);
		final GridSpork newSpork = publishSpork.getGridSpork();

		if (db.isUnsatisfied()) {
			throw new IllegalStateException("Unable to locate spork database bean.");
		}

		final Map<Type, GridSpork> entries = Map.of(MINT_STORAGE, db.get().getMintStorage(),
			MINT_SUPPLY, db.get().getMintSupply(),
			VESTING_STORAGE, db.get().getVestingStorage()
		);

		final GridSpork oldSpork = entries.get(newSpork.getType());

		if (Objects.isNull(oldSpork)) {
			log.atError().log("Received unsupported spork type - ignoring.");
			return; /* Bail out on unsupported type */
		}

		if (oldSpork.isOlderThan(newSpork) && newSpork.isValidSignature()) {
			db.get().set(newSpork);

			final Instance<Topology> topology = CDI.current().select(Topology.class);

			if (topology.isResolvable()) {
				// TODO: Handle errors better rather than sending Optional.empty()
				P2PClient.sendAll(publishSpork, topology.get(), Optional.empty());
			}
		}
	}
}
