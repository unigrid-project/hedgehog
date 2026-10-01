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
import java.util.Optional;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.chunk.TypedCodec;

@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class StorageSporkDecoder implements TypedCodec<GridSpork.Type>, ChunkDecoder<StorageSpork.SporkData> {
	private static final int RESERVED_BYTES = 6;

	@Override
	public Optional<StorageSpork.SporkData> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final StorageSpork.SporkData data = new StorageSpork.SporkData();

		data.setMaxBytesPerNode(in.readLong());
		data.setChunkSize(in.readInt());
		data.setFragmentSize(in.readInt());
		data.setOuterParityPercent(in.readUnsignedShort());
		data.setMaxOuterDataChunks(in.readUnsignedShort());
		data.setInnerParityPercent(in.readUnsignedShort());
		data.setMaxParityPercent(in.readUnsignedShort());
		data.setRepairIntervalMinutes(in.readInt());
		data.setTombstoneDays(in.readUnsignedShort());
		data.setManifestCopies(in.readUnsignedByte());
		data.setPlacementSlack(in.readUnsignedByte());
		data.setRepairThresholdPercent(in.readUnsignedByte());
		data.setExtraPoolPercent(in.readUnsignedByte());
		in.skipBytes(RESERVED_BYTES);
		return Optional.of(data);
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.STORAGE;
	}
}
