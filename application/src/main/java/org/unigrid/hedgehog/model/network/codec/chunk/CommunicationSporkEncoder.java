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
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.codec.api.ChunkEncoder;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;

@Chunk(type = ChunkType.ENCODER, group = ChunkGroup.GRIDSPORK)
public class CommunicationSporkEncoder implements TypedCodec<GridSpork.Type>, ChunkEncoder<CommunicationSpork.SporkData> {
	/*
	    Chunk format:
	    0..............................................................63
	    [         << Spork Header (AbstractGridSporkDecoder) >>        ]
	    [  group size  ][  reorganize period seconds   ][ chains/group ]
	    [clients/chain ][     chain capacity bytes     ][  retention   ]
	    [       max entry bytes        ][ idle seconds ][    flags     ]
	    [                           reserved                           ]
	*/
	private static final int RESERVED_BYTES = 8;

	@Override
	public void encodeChunk(ChannelHandlerContext ctx, CommunicationSpork.SporkData data, ByteBuf out)
		throws Exception {

		out.writeShort(data.getGroupSize());
		out.writeInt((int) data.getReorganizePeriodSeconds());
		out.writeShort(data.getChainsPerGroup());
		out.writeShort(data.getClientsPerChain());
		out.writeInt((int) data.getChainCapacityBytes());
		out.writeShort(data.getRetentionSeconds());
		out.writeInt((int) data.getMaxEntryBytes());
		out.writeShort(data.getSessionIdleSeconds());
		out.writeShort(data.getCommunicationFlags());
		out.writeZero(RESERVED_BYTES);
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.COMMUNICATION;
	}
}
