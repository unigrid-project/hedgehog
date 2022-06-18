/*
    Unigrid Hedgehog 
    Copyright © 2021-2022 The Unigrid Foundation

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
import java.math.BigDecimal;
import java.util.HashMap;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintStorage;

@Chunk(chunkType = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class MintStorageDecoder<T> extends GridSporkDecoder {

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
	public MintStorage createInstance() {
		return new MintStorage();
	}

	@Override
	public void decodeData(GridSpork spork, ByteBuf in) throws Exception {
		spork.setData(getDecodedData(in));
	}

	@Override
	public void decodePreviousData(GridSpork spork, ByteBuf in) throws Exception {
		spork.setPreviousData(getDecodedData(in)); /* TODO: Add support for DELTA */
	}

	@Override
	public GridSpork.Type getSporkType() {
		return GridSpork.Type.MINT_STORAGE;
	}
}
