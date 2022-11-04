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
import java.util.List;
import lombok.NoArgsConstructor;
import static org.unigrid.hedgehog.model.network.codec.FrameDecoder.PACKET_SIZE_KEY;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;

public class PublishSporkDecoder extends AbstractReplayingDecoder<PublishSpork> implements PacketDecoder<PublishSpork> {
	/*
	    Packet format:
	    0.............................63.............................128
	    [ type ][resrvd][ packet size  ][          reserved            ]
            [ size ][ signature (size long)                              >>]
	    [ size ][ public key (size long)                             >>]
	    [<<                       spork data                         >>]
	*/
	@Override
	public PublishSpork typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		System.out.println("psd");
		System.out.println(ctx.channel().attr(Packet.KEY).get());
		System.out.println(ctx.channel().attr(PACKET_SIZE_KEY).get());
		return null;
	}

	@Override
	public void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		System.out.println("psd");
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.PUBLISH_SPORK;
	}
}
