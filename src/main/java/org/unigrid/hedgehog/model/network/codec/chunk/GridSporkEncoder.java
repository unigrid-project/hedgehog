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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.util.Optional;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.collection.OptionalMap;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkScanner;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.codec.api.ChunkEncoder;

@Slf4j
public abstract class GridSporkEncoder<T extends Packet> extends MessageToByteEncoder<T> implements ChunkEncoder<GridSpork> {
	private final OptionalMap<GridSpork.Type, ChunkEncoder> encoders;

	protected GridSporkEncoder() {
		encoders = ChunkScanner.scan(ChunkType.ENCODER, ChunkGroup.GRIDSPORK);
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
	public void encodeChunk(ChannelHandlerContext ctx, GridSpork spork, ByteBuf out) throws Exception {
		final Optional<ChunkEncoder> ce = encoders.getOptional(spork.getType());
		System.out.println(ce + " [e] " + spork.getType());

		if (ce.isPresent()) {
			System.out.println("true");
			out.writeShort(FrameDecoder.MAGIC);
			out.writeShort(Packet.Type.PUBLISH_SPORK.getValue());

			@Cleanup("release")
			final ByteBuf data = Unpooled.buffer();

			data.writeShort(spork.getType().getValue());
			data.writeShort(spork.getFlags());
			data.writeZero(4 /* 32 bits */);
			data.writeLong(spork.getTimeStamp().getEpochSecond());
			data.writeLong(spork.getPreviousTimeStamp().getEpochSecond());
			data.writeZero(8 /* 64 bits */);

			out.writeInt(data.writerIndex());
			out.writeBytes(data);

			//ce.get().encodeChunk(ctx, spork, out);
		} else {
			System.out.println("false");
			//out.clear();
		}
	}
}
