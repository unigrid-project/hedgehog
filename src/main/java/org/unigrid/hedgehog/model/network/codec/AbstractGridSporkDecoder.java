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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.collection.OptionalMap;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkScanner;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.codec.api.ChunkDecoder;

@Slf4j
public abstract class AbstractGridSporkDecoder<T extends Packet> extends AbstractReplayingDecoder<T> {

	private final OptionalMap<GridSpork.Type, ChunkDecoder> decoders;

	protected AbstractGridSporkDecoder() {
		decoders = ChunkScanner.scan(ChunkType.DECODER, ChunkGroup.GRIDSPORK);
	}

	/*
	    Packet format:
	    0..............................................................63
	    [                << Frame Header (FrameDecoder) >>             ]
	    [     type     ][    flags     ][           reserved           ]
            [                           timestamp                          ]
	    [                      previous timpestamp                     ]
	    [                           reserved                           ]
	    [                       << spork data >>                       ]
	    [                    << spork delta data >>                    ]
	    [     size     ][             signature (size long)          >>]
	*/
	public Optional<GridSpork> decodeGridSpork(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final GridSpork.Type type = GridSpork.Type.get(in.readShort());
		final Optional<ChunkDecoder> cd = decoders.getOptional(type);

		if (cd.isPresent()) {
			log.atTrace().log("decoding spork chunk of type {}", type);
			final GridSpork gridSpork = GridSpork.create(type);

			gridSpork.setFlags(in.readShort());
			in.skipBytes(4 /* 32 bits */);
			gridSpork.setTimeStamp(Instant.ofEpochSecond(in.readLong()));
			gridSpork.setPreviousTimeStamp(Instant.ofEpochSecond(in.readLong()));
			in.skipBytes(8 /* 64 bits */);

			gridSpork.setData((ChunkData) cd.get().decodeChunk(ctx, in).get());
			gridSpork.setPreviousData((ChunkData) cd.get().decodeChunk(ctx, in).get());

			final int signatureLength = in.readUnsignedShort();
			final byte[] signature = new byte[signatureLength];

			in.readBytes(signature);
			gridSpork.setSignature(signature);

			log.atTrace().log(() -> ByteBufUtil.prettyHexDump(in));
			return Optional.of(gridSpork);
		}

		log.atError().log("Unable to handle spork chunk of type {}", type);
		return Optional.empty();
	}
}
