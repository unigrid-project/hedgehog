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
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.AttributeKey;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.channel.ChannelCodec;
import org.unigrid.hedgehog.model.network.packet.Packet;

@ChannelCodec(priority = -10)
public class FrameDecoder extends LengthFieldBasedFrameDecoder {
	public static final int MAGIC = 0xBABE;
	public static final AttributeKey<Integer> PACKET_SIZE_KEY = AttributeKey.valueOf("PACKET_SIZE");

	/*
	    Packet format:
	    0..............................................................63
	    [    0xBABE    ][     type     ][         packet size          ]
	    [                  << packet specific data >>                  ]
	*/
	public FrameDecoder() {
		super(Network.MAX_DATA_SIZE, 4, 4, 0, 8);
	}

	@Override
	protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		in.markReaderIndex();
		final int magic = in.readUnsignedShort();

		if (MAGIC == magic) {
			/* Tell the pipeline what packet to handle next */
			ctx.channel().attr(Packet.KEY).set(Packet.Type.get(in.readShort()));
			ctx.channel().attr(PACKET_SIZE_KEY).set(in.readInt());

			in.resetReaderIndex();
			return super.decode(ctx, in);
		}

		throw new InvalidFrameMagicNumberException();
	}
}
