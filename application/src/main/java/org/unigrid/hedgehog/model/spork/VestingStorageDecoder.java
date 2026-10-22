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
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.chunk.TypedCodec;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;

@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class VestingStorageDecoder implements TypedCodec<GridSpork.Type>, ChunkDecoder<VestingStorage.SporkData> {
	private interface Time<T> {
		T of(long seconds, long nanos);
	}

	/*
	    Chunk format:
	    0..............................................................63
	    [         << Spork Header (AbstractGridSporkDecoder) >>        ]
	    [    n= num vestings   ][               reserved               ]
	   n[                     << address (0-term) >>                   ]
	    [                     vesting start (seconds)                  ]
	    [      start (nanos)       ]
	    [                    vesting duration (seconds)                ]
	    [     duration (nanos)     ][          vesting parts           ]
	    [      vesting cliff       ][         vesting percent          ]
	    [      vesting block       ]
	    [             << amount (0-term, empty if none) >>         ...n]
	*/
	@Override
	public Optional<VestingStorage.SporkData> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final int entries = in.readUnsignedMedium();

		if (entries > VestingStorage.SporkData.MAX_ENTRIES) {
			throw new CorruptedFrameException("A vesting storage of " + entries + " vestings exceeds the cap of "
				+ VestingStorage.SporkData.MAX_ENTRIES);
		}

		final VestingStorage.SporkData data = new VestingStorage.SporkData();
		final HashMap<Address, VestingStorage.SporkData.Vesting> vests = new HashMap<>();

		in.skipBytes(5 /* 40 bits */);

		while (in.readableBytes() > 0 && vests.size() < entries) {
			final Address address = new Address(ByteBufUtils.readNullTerminatedString(in, Address.MAX_LENGTH));
			final VestingStorage.SporkData.Vesting vesting = new VestingStorage.SporkData.Vesting();

			vesting.setStart(timeOf("start", in.readLong(), in.readInt(), Instant::ofEpochSecond));
			vesting.setDuration(timeOf("duration", in.readLong(), in.readInt(), Duration::ofSeconds));
			vesting.setParts(in.readInt());
			vesting.setCliff(in.readInt());
			vesting.setPercent(in.readInt());
			vesting.setBlock(in.readInt());

			vesting.setAmount(SporkAmount.readOptional(in).orElse(null));

			vests.put(address, vesting);
		}

		if (entries == vests.size()) {
			data.setVestingAddresses(vests);
			return Optional.of(data);
		}

		return Optional.empty();
	}

	/* A peer may send any number of seconds, and one past the range of time would fail the stream with an error that
	   says nothing of the frame */
	private static <T> T timeOf(String what, long seconds, int nanos, Time<T> time) {
		try {
			return time.of(seconds, nanos);
		} catch (DateTimeException | ArithmeticException ex) {
			throw new CorruptedFrameException("A vesting " + what + " of " + seconds + " seconds and " + nanos
				+ " nanoseconds is out of the range of time");
		}
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.VESTING_STORAGE;
	}
}
