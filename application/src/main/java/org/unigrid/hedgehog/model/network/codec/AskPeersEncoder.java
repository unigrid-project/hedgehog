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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;
import org.unigrid.hedgehog.model.network.packet.AskPeers;
import org.unigrid.hedgehog.model.network.packet.Packet;

@Sharable
public class AskPeersEncoder extends AbstractMessageToByteEncoder<AskPeers> implements PacketEncoder<AskPeers> {
	/*
	    Packet format:
	    0..............................................................63
	    [                << Frame Header (FrameDecoder) >>             ]
	    [    amount    ][                  reserved                    ]
	*/
	@Override
	public Optional<ByteBuf> encode(ChannelHandlerContext ctx, AskPeers askPeers) throws Exception {
		final ByteBuf out = Unpooled.buffer();

		out.writeShort(askPeers.getAmount());
		out.writeZero(6 /* 48 bits */);

		return Optional.of(out);
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.ASK_PEERS;
	}
}
