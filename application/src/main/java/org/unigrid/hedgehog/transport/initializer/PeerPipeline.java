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

package org.unigrid.hedgehog.transport.initializer;

import io.netty.channel.ChannelHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.GridnodeDecoder;
import org.unigrid.hedgehog.model.network.codec.GridnodeEncoder;
import org.unigrid.hedgehog.model.network.codec.HelloDecoder;
import org.unigrid.hedgehog.model.network.codec.HelloEncoder;
import org.unigrid.hedgehog.model.network.codec.PingDecoder;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.codec.PublishPeersDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishPeersEncoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkEncoder;
import org.unigrid.hedgehog.transport.handler.HelloChannelHandler;
import org.unigrid.hedgehog.transport.handler.PeerActivityHandler;
import org.unigrid.hedgehog.transport.handler.PingChannelHandler;
import org.unigrid.hedgehog.transport.handler.PublishGridnodeChannelHandler;
import org.unigrid.hedgehog.transport.handler.PublishPeersChannelHandler;
import org.unigrid.hedgehog.transport.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.transport.handler.SporkProtocol;
import org.unigrid.hedgehog.transport.handler.UnclaimedFrameHandler;
import org.unigrid.hedgehog.transport.initializer.RegisterQuicChannelInitializer.Type;
import org.unigrid.hedgehog.transport.schedule.LedgerStatusSchedule;
import org.unigrid.hedgehog.transport.schedule.PingSchedule;
import org.unigrid.hedgehog.transport.schedule.PublishGridnodeSchedule;
import org.unigrid.hedgehog.transport.schedule.PublishPeersSchedule;
import org.unigrid.hedgehog.transport.schedule.PublishSporkSchedule;
import org.unigrid.hedgehog.transport.schedule.Schedulable;

public final class PeerPipeline {
	private PeerPipeline() {
		/* Static helpers only */
	}

	/* The ledger stages, and the schedule that announces streams to the ledger, are there only when the node offers the
	   ledger protocol, as a node without it, such as one of an earlier release, would not know their packets. The
	   gridnode and storage stages, and the schedule that announces gridnodes, are there only when the node offers the
	   proxy protocol, which only a node run with --preview does. The frames of the stages that the node does without
	   are let go of by the frame decoder as they arrive, and any other frame that no stage takes is dropped behind
	   the last of them. */
	public static List<ChannelHandler> handlers(final Type side, final boolean ledger, final boolean preview) {
		final List<ChannelHandler> handlers = new ArrayList<>(Arrays.asList(
			new PeerActivityHandler(),
			new LoggingHandler(LogLevel.DEBUG),
			FrameDecoder.forMainStream(ledger, preview),
			hello(side)
		));

		if (preview) {
			handlers.addAll(List.of(new GridnodeEncoder(), new GridnodeDecoder()));
		}

		handlers.addAll(List.of(
			new PingEncoder(), new PingDecoder(),
			new PublishSporkEncoder(), new PublishSporkDecoder(SporkProtocol::inTheLayoutOfThisRelease),
			new PublishPeersEncoder(), new PublishPeersDecoder(),
			new PingChannelHandler(), new PublishSporkChannelHandler()
		));

		if (side == Type.SERVER) {
			handlers.add(new HelloChannelHandler());
		}

		handlers.add(new PublishPeersChannelHandler());

		if (preview) {
			handlers.add(new PublishGridnodeChannelHandler());
			handlers.addAll(StoragePipeline.handlers());
		}

		if (ledger) {
			handlers.addAll(LedgerPipeline.handlers());
		}

		handlers.add(new UnclaimedFrameHandler());
		return handlers;
	}

	public static List<Schedulable> schedulers(final boolean ledger, final boolean preview) {
		final List<Schedulable> schedulers = new ArrayList<>(Arrays.asList(
			new PingSchedule(),
			new PublishPeersSchedule(),
			new PublishSporkSchedule()
		));

		if (preview) {
			schedulers.add(new PublishGridnodeSchedule());
		}

		if (ledger) {
			schedulers.add(new LedgerStatusSchedule());
		}

		return schedulers;
	}

	private static ChannelHandler hello(final Type side) {
		return side == Type.SERVER ? new HelloDecoder() : new HelloEncoder();
	}
}
