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
import io.netty.handler.codec.ReplayingDecoder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.model.collection.OptionalMap;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkScanner;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;
import org.unigrid.hedgehog.model.network.codec.api.ChunkDecoder;
import org.unigrid.hedgehog.model.network.codec.api.ChunkEncoder;
import org.unigrid.hedgehog.model.network.codec.api.TypedCodec;

public abstract class GridSporkDecoder<T extends Packet> extends ReplayingDecoder<T> implements ChunkDecoder<GridSpork> {
	private final OptionalMap<GridSpork.Type, ChunkDecoder> decoders;

	protected GridSporkDecoder() {
		decoders = ChunkScanner.scan(ChunkType.DECODER, ChunkGroup.GRIDSPORK);
	}

	/*
	    Packet format:
	    0..............................................................63
	    [     type     ][    flags     ][           reserved           ]
            [                           timestamp                          ]
	    [                      previous timpestamp                     ]
	    [                           reserved                           ]
	    [                        size spork data                       ]
	    [                       << spork data >>                       ]
	    [                     size spork delta data                    ]
	    [                    << spork delta data >>                    ]
	    [     size     ][             signature (size long)          >>]
	*/
	@Override
	public Optional<GridSpork> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final GridSpork.Type type = GridSpork.Type.get(in.readShort());
		final Optional<ChunkDecoder> cd = decoders.getOptional(type);
		System.out.println(cd + " [d] " + type);

		if (cd.isPresent()) {
			final GridSpork<?, ?> gridSpork = GridSpork.create(type);

			gridSpork.setFlags(in.readShort());
			in.skipBytes(4 /* 32 bits */);
			gridSpork.setTimeStamp(Instant.ofEpochSecond(in.readLong()));
			gridSpork.setPreviousTimeStamp(Instant.ofEpochSecond(in.readLong()));
			in.skipBytes(8 /* 64 bits */);

			/* TODO: Do the chunk here */

			//gridSpork.setSignatureData(signatureData);

			return Optional.of(gridSpork);
		}

		//in.clear();
		return Optional.empty();
	}
}
