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
import java.util.HexFormat;
import java.util.Set;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.chunk.TypedCodec;
import org.unigrid.hedgehog.model.spork.LegacyWhiteList.SporkData;

/** Writes the list as a two-byte count, then the 20-byte hashes in ascending order. */
@Chunk(type = ChunkType.ENCODER, group = ChunkGroup.GRIDSPORK)
public class LegacyWhiteListEncoder implements TypedCodec<GridSpork.Type>, ChunkEncoder<SporkData> {
	/*
	    Chunk format:
	    0..............................................................63
	    [         << Spork Header (AbstractGridSporkDecoder) >>        ]
	    [     n= num hashes    ]
	   n[                hash160 (20 bytes, ascending)                 ]
	*/

	/** Validates first: the signatures cover these bytes, so a list peers would refuse must never be signed. */
	@Override
	public void encodeChunk(ChannelHandlerContext ctx, SporkData data, ByteBuf out) throws Exception {
		data.validate();

		final Set<String> hashes = data.getApprovedLegacyAddresses();
		final HexFormat hex = HexFormat.of();

		out.writeShort(hashes.size());
		hashes.stream().sorted().forEach(hash -> out.writeBytes(hex.parseHex(hash)));
	}

	/** Tells the chunk scanner which spork type this encoder handles. */
	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.LEGACY_WHITELIST;
	}
}
