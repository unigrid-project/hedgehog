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

package org.unigrid.hedgehog.model.network.codec.chunk;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.codec.api.ChunkDecoder;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;

@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class CommunicationSporkDecoder implements TypedCodec<GridSpork.Type>, ChunkDecoder<CommunicationSpork.SporkData> {
	private static final int RESERVED_BYTES = 8;

	@Override
	public Optional<CommunicationSpork.SporkData> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final CommunicationSpork.SporkData data = new CommunicationSpork.SporkData();

		data.setGroupSize(in.readUnsignedShort());
		data.setReorganizePeriodSeconds(in.readUnsignedInt());
		data.setChainsPerGroup(in.readUnsignedShort());
		data.setClientsPerChain(in.readUnsignedShort());
		data.setChainCapacityBytes(in.readUnsignedInt());
		data.setRetentionSeconds(in.readUnsignedShort());
		data.setMaxEntryBytes(in.readUnsignedInt());
		data.setSessionIdleSeconds(in.readUnsignedShort());
		data.setCommunicationFlags(in.readUnsignedShort());
		in.skipBytes(RESERVED_BYTES);
		return Optional.of(data);
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.COMMUNICATION;
	}
}
