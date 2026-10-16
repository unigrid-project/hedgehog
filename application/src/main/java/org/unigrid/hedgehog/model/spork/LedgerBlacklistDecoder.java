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
import io.netty.handler.codec.CorruptedFrameException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.chunk.TypedCodec;

@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class LedgerBlacklistDecoder implements TypedCodec<GridSpork.Type>, ChunkDecoder<LedgerBlacklist.SporkData> {
	/*
	    Chunk format:
	    0..............................................................63
	    [         << Spork Header (AbstractGridSporkDecoder) >>        ]
	    [     n= num hashes    ]
	   n[                hash160 (20 bytes, ascending)                 ]
	*/
	@Override
	public Optional<LedgerBlacklist.SporkData> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final int count = in.readUnsignedShort();

		if (count > LedgerBlacklist.SporkData.MAX_ENTRIES) {
			throw new CorruptedFrameException("A blacklist of " + count + " addresses exceeds the cap of "
				+ LedgerBlacklist.SporkData.MAX_ENTRIES);
		}

		final Set<String> hashes = new TreeSet<>();
		final byte[] hash = new byte[LedgerBlacklist.SporkData.HASH_BYTES];

		for (int i = 0; i < count; i++) {
			in.readBytes(hash);
			hashes.add(HexFormat.of().formatHex(hash));
		}

		final LedgerBlacklist.SporkData data = new LedgerBlacklist.SporkData();

		data.setLegacyAddresses(hashes);
		return Optional.of(data);
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.LEDGER_BLACKLIST;
	}
}
