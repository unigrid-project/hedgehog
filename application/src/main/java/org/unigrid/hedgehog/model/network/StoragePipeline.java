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

package org.unigrid.hedgehog.model.network;

import io.netty.channel.ChannelHandler;
import java.util.List;
import org.unigrid.hedgehog.model.network.codec.DeleteGroupDecoder;
import org.unigrid.hedgehog.model.network.codec.DeleteGroupEncoder;
import org.unigrid.hedgehog.model.network.codec.FetchFragmentDecoder;
import org.unigrid.hedgehog.model.network.codec.FetchFragmentEncoder;
import org.unigrid.hedgehog.model.network.codec.FragmentReplyDecoder;
import org.unigrid.hedgehog.model.network.codec.FragmentReplyEncoder;
import org.unigrid.hedgehog.model.network.codec.FragmentStatusDecoder;
import org.unigrid.hedgehog.model.network.codec.FragmentStatusEncoder;
import org.unigrid.hedgehog.model.network.codec.HasFragmentDecoder;
import org.unigrid.hedgehog.model.network.codec.HasFragmentEncoder;
import org.unigrid.hedgehog.model.network.codec.StorageAckDecoder;
import org.unigrid.hedgehog.model.network.codec.StorageAckEncoder;
import org.unigrid.hedgehog.model.network.codec.StoreFragmentDecoder;
import org.unigrid.hedgehog.model.network.codec.StoreFragmentEncoder;
import org.unigrid.hedgehog.model.network.handler.DeleteGroupChannelHandler;
import org.unigrid.hedgehog.model.network.handler.FetchFragmentChannelHandler;
import org.unigrid.hedgehog.model.network.handler.HasFragmentChannelHandler;
import org.unigrid.hedgehog.model.network.handler.StorageResponseChannelHandler;
import org.unigrid.hedgehog.model.network.handler.StoreFragmentChannelHandler;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.packet.StorageAck;

public final class StoragePipeline {
	private StoragePipeline() {
		/* Static helpers only */
	}

	/* Decoders forward what they do not own and handlers ignore foreign packets, so appending the storage
	   stages after the existing ones keeps every earlier packet on its old path. */
	public static List<ChannelHandler> handlers() {
		return List.of(new StoreFragmentEncoder(), new StoreFragmentDecoder(),
			new FetchFragmentEncoder(), new FetchFragmentDecoder(),
			new FragmentReplyEncoder(), new FragmentReplyDecoder(),
			new HasFragmentEncoder(), new HasFragmentDecoder(),
			new FragmentStatusEncoder(), new FragmentStatusDecoder(),
			new DeleteGroupEncoder(), new DeleteGroupDecoder(),
			new StorageAckEncoder(), new StorageAckDecoder(),
			new StoreFragmentChannelHandler(), new FetchFragmentChannelHandler(),
			new HasFragmentChannelHandler(), new DeleteGroupChannelHandler(),
			new StorageResponseChannelHandler<>(StorageAck.class),
			new StorageResponseChannelHandler<>(FragmentReply.class),
			new StorageResponseChannelHandler<>(FragmentStatus.class));
	}
}
