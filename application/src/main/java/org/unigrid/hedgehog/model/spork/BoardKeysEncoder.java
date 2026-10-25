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
import java.util.Objects;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.chunk.TypedCodec;

@Chunk(type = ChunkType.ENCODER, group = ChunkGroup.GRIDSPORK)
public class BoardKeysEncoder implements TypedCodec<GridSpork.Type>, ChunkEncoder<BoardKeys.SporkData> {
	/*
	    Chunk format:
	    0..............................................................63
	    [         << Spork Header (AbstractGridSporkDecoder) >>        ]
	    [n=changes]
	   n[                   << change (KeyChange) >>                   ]
	*/
	@Override
	public void encodeChunk(ChannelHandlerContext ctx, BoardKeys.SporkData data, ByteBuf out) throws Exception {
		/* The signatures cover these bytes, so what cannot be decoded again must not be signed */
		Validate.isTrue(Objects.nonNull(data.getChanges()) && data.getChanges().size() <= BoardKeyChain.MAX_CHANGES,
			"A chain of board keys holds at most %d changes", BoardKeyChain.MAX_CHANGES);

		out.writeByte(data.getChanges().size());
		data.getChanges().forEach(change -> change.write(out));
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.BOARD_KEYS;
	}
}
