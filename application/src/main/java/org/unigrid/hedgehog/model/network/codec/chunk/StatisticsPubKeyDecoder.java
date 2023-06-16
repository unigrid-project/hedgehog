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

package org.unigrid.hedgehog.model.network.codec.chunk;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.codec.api.ChunkDecoder;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.StatisticsPubKey;

@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class StatisticsPubKeyDecoder implements TypedCodec<GridSpork.Type>, ChunkDecoder {
	/*
	    Chunk format:
	    0..............................................................63
	    [         << Spork Header (AbstractGridSporkDecoder) >>        ]
	    [                           reserved                           ]
	    [                     << pubkey (0-term) >>                    ]
	*/
	@Override
	public Optional<StatisticsPubKey.SporkData> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final StatisticsPubKey.SporkData data = new StatisticsPubKey.SporkData();

		in.skipBytes(8 /* 64 bits */);
		data.setPublicKey(ByteBufUtils.readNullTerminatedString(in));

		return Optional.of(data);
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.STATISTICS_PUBKEY;
	}
}
