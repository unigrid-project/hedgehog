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
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.HashMap;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.codec.api.ChunkDecoder;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
import org.unigrid.hedgehog.model.spork.Cosmos;
import org.unigrid.hedgehog.model.spork.GridSpork;

@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class CosmosDecoder implements TypedCodec<GridSpork.Type>, ChunkDecoder<Cosmos.SporkData> {
	@Override
	public Optional<Cosmos.SporkData> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {

		final Cosmos.SporkData data = new Cosmos.SporkData();
		final HashMap<String, Object> cosm = new HashMap<>();
		final int entries = in.readMedium();

		in.skipBytes(5 );// 40 bits

		while (in.readableBytes() > 0 && cosm.size() < entries) {
			String key = ByteBufUtils.readNullTerminatedString(in);
			byte[] bytes = new byte[in.readableBytes()];

			Object obj = null;
			try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes); ObjectInputStream ois = new ObjectInputStream(
				bais)) {
				obj = ois.readObject();
			}
			cosm.put(key, obj);
		}

		if (entries == cosm.size()) {
			data.setParameters(cosm);
			return Optional.of(data);
		}

		return Optional.empty();
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.COSMOS;
	}
}