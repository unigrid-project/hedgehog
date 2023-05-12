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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.channel.ChannelCodec;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.Ping;

@ChannelCodec(priority = 1)
public class PingDecoder extends AbstractReplayingDecoder<Ping> implements PacketDecoder<Ping> {
	/*
	    Packet format:
	    0..............................................................63
	    [                << Frame Header (FrameDecoder) >>             ]
            [                       nano request time                      ]
	    R[                           reserved                          ]
	*/
	@Override
	public Optional<Ping> typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final Ping ping = new Ping();

		ping.setNanoTime(in.readLong());
		ping.setResponse((in.readByte() & 0x01) == 0x01);
		in.skipBytes(7 /* 56 bits */);

		return Optional.of(ping);
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.PING;
	}
}
