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
import io.netty.channel.ChannelHandlerContext;
import java.time.Instant;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.model.network.codec.api.Decodable;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.network.codec.api.DecodableGridSpork;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class GridSporkDecoder implements Decodable, DecodableGridSpork {
	/*
	    Packet format:
	    0.............................63.............................127
            [ type ][flags ][                reserved                      ]
	    [           timestamp          ][     previous timpestamp      ]
	    [           reserved           ][  data size   ][  delta size  ]
	    [                       << spork data >>                       ]
	    [                    << spork delta data >>                    ]
	*/
	@Override
	public Optional<? extends GridSpork> decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		if (getSporkType() == GridSpork.Type.get(in.readShort())) {
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

		in.resetReaderIndex();
		return Optional.empty();
	}
}
