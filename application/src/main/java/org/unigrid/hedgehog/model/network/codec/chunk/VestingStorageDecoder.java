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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.codec.api.ChunkDecoder;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.VestingStorage;

@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class VestingStorageDecoder implements TypedCodec<GridSpork.Type>, ChunkDecoder<VestingStorage.SporkData> {
	/*
	    Chunk format:
	    0..............................................................63
	    [         << Spork Header (AbstractGridSporkDecoder) >>        ]
	    [     n= num mints     ][               reserved               ]
	   n[                     << address (0-term) >>                   ]
	    [                     vesting start (seconds)                  ]
	    [                    vesting duration (seconds)                ]
	    [                          vesting parts			   ]
	    [                          vesting cliff			   ]
	    [                         vesting percent			   ]
	    [                       vesting block (0-term)		   ]
	    [                       vesting amount (0-term)	       ...n]
	*/
	@Override
	public Optional<VestingStorage.SporkData> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final VestingStorage.SporkData data = new VestingStorage.SporkData();
		final HashMap<Address, VestingStorage.SporkData.Vesting> vests = new HashMap<>();
		final int entries = in.readMedium();

		in.skipBytes(5 /* 40 bits */);

		while (in.readableBytes() > 0 && vests.size() < entries) {
			final Address address = new Address(ByteBufUtils.readNullTerminatedString(in));
			final VestingStorage.SporkData.Vesting vesting = new VestingStorage.SporkData.Vesting();

			vesting.setStart(Instant.ofEpochSecond(in.readLong()));
			vesting.setDuration(Duration.ofSeconds(in.readLong()));
			vesting.setParts(in.readInt());
			vesting.setCliff(in.readInt());
			vesting.setPercent(in.readInt());
			vesting.setBlock(new BigInteger(ByteBufUtils.readNullTerminatedString(in)));
			vesting.setAmount(new BigDecimal(ByteBufUtils.readNullTerminatedString(in)));

			vests.put(address, vesting);
		}

		if (entries == vests.size()) {
			data.setVestingAddresses(vests);
			return Optional.of(data);
		}

		return Optional.empty();
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.VESTING_STORAGE;
	}
}
