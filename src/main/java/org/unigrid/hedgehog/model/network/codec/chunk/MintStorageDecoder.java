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
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Optional;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.codec.api.ChunkDecoder;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.network.codec.api.TypedCodec;

@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class MintStorageDecoder implements TypedCodec<GridSpork.Type>, ChunkDecoder<MintStorage> {
	/*@Override
	public Optional<? extends GridSpork> decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final GridSpork<?, ?> gridSpork = GridSpork.create(GridSpork.Type.get(in.readShort()));

		gridSpork.setFlags(in.readShort());

		in.skipBytes(4  32 bits );

		//if (getSporkType() == GridSpork.Type.get(in.readShort())) {
			final GridSpork spork = createInstance();

			spork.setType(getSporkType());
			spork.setFlags((short) in.readShort());
			in.skipBytes(12);

			spork.setTimeStamp(Instant.ofEpochSecond(in.readLong()));
			spork.setPreviousTimeStamp(Instant.ofEpochSecond(in.readLong()));
			in.skipBytes(8);

			final int dataSize = in.readInt();
			final int deltaSize = in.readInt();

			if (dataSize + deltaSize == in.readableBytes()) {
				decodeData(spork, in);
				decodePreviousData(spork, in);

				return Optional.of(spork);
			} else {
				System.err.println("Spork data/delta size does not equal the amount of pending bytes.");
			}
		}

		//in.resetReaderIndex();
		//return Optional.empty();

		return Optional.of(gridSpork);
	}*/
	private MintStorage.SporkData getDecodedData(ByteBuf in) throws Exception {
		final MintStorage.SporkData data = new MintStorage.SporkData();
		final HashMap<MintStorage.SporkData.Location, BigDecimal> mints = new HashMap<>();

		while (in.readableBytes() > 0) {
			final Address address = new Address(ByteBufUtils.readNullTerminatedString(in));
			final int height = in.readInt();
			final BigDecimal amount = new BigDecimal(ByteBufUtils.readNullTerminatedString(in));

			mints.put(new MintStorage.SporkData.Location(address, height), amount);
		}

		data.setMints(mints);
		return data;
	}

	@Override
	public Optional<MintStorage> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.MINT_STORAGE;
	}
}
