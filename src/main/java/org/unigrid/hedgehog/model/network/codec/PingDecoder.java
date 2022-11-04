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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.util.ReferenceCountUtil;
import java.util.List;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;

public class PingDecoder extends AbstractReplayingDecoder<Ping> implements PacketDecoder<Ping> {
	/*
	    Packet format:
	    0..............................................................63
            [                       nano request time                      ]
	    R[                           reserved                          ]
	*/
	/*@Override
	public void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
	}*/

	@Override
	public Ping typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		System.out.println("pingDecode");
		final Ping ping = new Ping();

		//ping.setNanoTime(in.readLong());
		//ping.setResponse((in.readByte() & 0x01) == 0x01);
		//in.skipBytes(7 /* 56 bits */);
		//out.add(PublishSpork.builder().build());
		//ctx.fireChannelRead(in);
		//ctx.channel()
		//ctx.fireChannelRead(in);
		//ReferenceCountUtil.release(in);
		return ping;
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.PING;
	}
}
