/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.network;

import io.netty.channel.ChannelHandler;
import java.util.List;
import org.unigrid.hedgehog.model.network.codec.ChainEntryDecoder;
import org.unigrid.hedgehog.model.network.codec.ChainEntryEncoder;
import org.unigrid.hedgehog.model.network.codec.ChainHeadsDecoder;
import org.unigrid.hedgehog.model.network.codec.ChainHeadsEncoder;
import org.unigrid.hedgehog.model.network.codec.EntryRefusedDecoder;
import org.unigrid.hedgehog.model.network.codec.EntryRefusedEncoder;
import org.unigrid.hedgehog.model.network.codec.FetchEntriesDecoder;
import org.unigrid.hedgehog.model.network.codec.FetchEntriesEncoder;
import org.unigrid.hedgehog.model.network.codec.JoinChainDecoder;
import org.unigrid.hedgehog.model.network.codec.JoinChainEncoder;
import org.unigrid.hedgehog.model.network.codec.JoinReplyDecoder;
import org.unigrid.hedgehog.model.network.codec.JoinReplyEncoder;
import org.unigrid.hedgehog.model.network.codec.LeaveChainDecoder;
import org.unigrid.hedgehog.model.network.codec.LeaveChainEncoder;
import org.unigrid.hedgehog.model.network.handler.ChainEntryChannelHandler;
import org.unigrid.hedgehog.model.network.handler.ChainHeadsChannelHandler;
import org.unigrid.hedgehog.model.network.handler.EntryRefusedChannelHandler;
import org.unigrid.hedgehog.model.network.handler.FetchEntriesChannelHandler;
import org.unigrid.hedgehog.model.network.handler.JoinChainChannelHandler;
import org.unigrid.hedgehog.model.network.handler.LeaveChainChannelHandler;
import org.unigrid.hedgehog.model.network.handler.StorageResponseChannelHandler;
import org.unigrid.hedgehog.model.network.packet.JoinReply;

public final class ChainPipeline {
	private ChainPipeline() {
		/* Static helpers only */
	}

	/* Appended after the storage stages for the same reason those follow the base ones: decoders forward what
	   they do not own and handlers ignore foreign packets, so every earlier packet keeps its path */
	public static List<ChannelHandler> handlers() {
		return List.of(new JoinChainEncoder(), new JoinChainDecoder(),
			new JoinReplyEncoder(), new JoinReplyDecoder(),
			new ChainEntryEncoder(), new ChainEntryDecoder(),
			new ChainHeadsEncoder(), new ChainHeadsDecoder(),
			new FetchEntriesEncoder(), new FetchEntriesDecoder(),
			new EntryRefusedEncoder(), new EntryRefusedDecoder(),
			new LeaveChainEncoder(), new LeaveChainDecoder(),
			new JoinChainChannelHandler(), new LeaveChainChannelHandler(),
			new ChainEntryChannelHandler(), new ChainHeadsChannelHandler(),
			new FetchEntriesChannelHandler(), new EntryRefusedChannelHandler(),
			new StorageResponseChannelHandler<>(JoinReply.class));
	}
}
