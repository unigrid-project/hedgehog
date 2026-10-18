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

package org.unigrid.hedgehog.model.spork;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.chunk.TypedCodec;

@Chunk(type = ChunkType.ENCODER, group = ChunkGroup.GRIDSPORK)
public class StorageSporkEncoder implements TypedCodec<GridSpork.Type>, ChunkEncoder<StorageSpork.SporkData> {
	/*
	    Chunk format:
	    0..............................................................63
	    [         << Spork Header (AbstractGridSporkDecoder) >>        ]
	    [                      max bytes per node                      ]
	    [          chunk size          ][        fragment size         ]
	    [ outer parity % ][ max outer   ][ inner parity % ][max parity %]
	    [   repair interval minutes    ][tombstone days][ copy ][ slack]
	    [thresh][ pool ][                  reserved                    ]
	*/
	private static final int RESERVED_BYTES = 6;

	@Override
	public void encodeChunk(ChannelHandlerContext ctx, StorageSpork.SporkData data, ByteBuf out) throws Exception {
		out.writeLong(data.getMaxBytesPerNode());
		out.writeInt(data.getChunkSize());
		out.writeInt(data.getFragmentSize());
		out.writeShort(data.getOuterParityPercent());
		out.writeShort(data.getMaxOuterDataChunks());
		out.writeShort(data.getInnerParityPercent());
		out.writeShort(data.getMaxParityPercent());
		out.writeInt(data.getRepairIntervalMinutes());
		out.writeShort(data.getTombstoneDays());
		out.writeByte(data.getManifestCopies());
		out.writeByte(data.getPlacementSlack());
		out.writeByte(data.getRepairThresholdPercent());
		out.writeByte(data.getExtraPoolPercent());
		out.writeZero(RESERVED_BYTES);
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.STORAGE;
	}
}
