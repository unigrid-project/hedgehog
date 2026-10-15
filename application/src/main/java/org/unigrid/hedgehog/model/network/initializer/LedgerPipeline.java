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

package org.unigrid.hedgehog.model.network.initializer;

import io.netty.channel.ChannelHandler;
import java.util.List;
import org.unigrid.hedgehog.model.network.codec.LedgerBlockBatchDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerBlockBatchEncoder;
import org.unigrid.hedgehog.model.network.codec.LedgerBlockRequestDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerBlockRequestEncoder;
import org.unigrid.hedgehog.model.network.codec.LedgerConsensusDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerConsensusEncoder;
import org.unigrid.hedgehog.model.network.codec.LedgerHeartbeatDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerHeartbeatEncoder;
import org.unigrid.hedgehog.model.network.codec.LedgerStatusDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerStatusEncoder;
import org.unigrid.hedgehog.model.network.codec.LedgerTransactionDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerTransactionEncoder;
import org.unigrid.hedgehog.model.network.handler.LedgerBlockRequestChannelHandler;
import org.unigrid.hedgehog.model.network.handler.LedgerConsensusChannelHandler;
import org.unigrid.hedgehog.model.network.handler.LedgerHeartbeatChannelHandler;
import org.unigrid.hedgehog.model.network.handler.LedgerStatusChannelHandler;
import org.unigrid.hedgehog.model.network.handler.LedgerTransactionChannelHandler;
import org.unigrid.hedgehog.model.network.handler.StorageResponseChannelHandler;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockBatch;

public final class LedgerPipeline {
	private LedgerPipeline() {
		/* Static helpers only */
	}

	/* Fresh handlers for every stream, as the decoders keep state. They follow the storage stages, and like those they
	   forward what they do not own, so every earlier packet stays on its old path. */
	public static List<ChannelHandler> handlers() {
		return List.of(new LedgerTransactionEncoder(), new LedgerTransactionDecoder(),
			new LedgerConsensusEncoder(), new LedgerConsensusDecoder(),
			new LedgerHeartbeatEncoder(), new LedgerHeartbeatDecoder(),
			new LedgerStatusEncoder(), new LedgerStatusDecoder(),
			new LedgerBlockRequestEncoder(), new LedgerBlockRequestDecoder(),
			new LedgerBlockBatchEncoder(), new LedgerBlockBatchDecoder(),
			new LedgerTransactionChannelHandler(), new LedgerConsensusChannelHandler(),
			new LedgerHeartbeatChannelHandler(), new LedgerStatusChannelHandler(),
			new LedgerBlockRequestChannelHandler(), new StorageResponseChannelHandler<>(LedgerBlockBatch.class));
	}
}
