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

package org.unigrid.hedgehog.model.network.codec.chunk;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.codec.api.ChunkDecoder;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.VestingStorage;

@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class VestingStorageDecoder implements TypedCodec<GridSpork.Type>, ChunkDecoder<VestingStorage> {
	private VestingStorage.SporkData getDecodedData(ByteBuf in) throws Exception {
		return null;
	}

	@Override
	public Optional<VestingStorage> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		return Optional.empty();
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.VESTING_STORAGE;
	}
}
