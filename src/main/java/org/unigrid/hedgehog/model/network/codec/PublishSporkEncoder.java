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
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.Cleanup;
import org.unigrid.hedgehog.model.network.chunk.ChunkScanner;
import static org.unigrid.hedgehog.model.network.codec.FrameDecoder.PACKET_SIZE_KEY;
import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;
import org.unigrid.hedgehog.model.network.codec.chunk.GridSporkEncoder;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;

@Sharable
public class PublishSporkEncoder extends GridSporkEncoder<PublishSpork> implements PacketEncoder<PublishSpork> {
	/*
	    Packet format:
	    0.............................63.............................128
	    [ type ][resrvd][ packet size  ][          reserved            ]
            [ size ][ signature (size long)                              >>]
	    [<<                       spork data                         >>]
	*/
	@Override
	public void encode(ChannelHandlerContext ctx, PublishSpork publishSpork, ByteBuf out) throws Exception {
		System.out.println("encode");
		encodeChunk(ctx, publishSpork.getGridSpork(), out);
		
		//System.out.println("encode");
		//System.out.println(publishSpork);

		//@Cleanup("release")
		//final ByteBuf data = Unpooled.buffer();
		//data.writeShort(publishSpork.getSignatureData().length);
		//data.writeBytes(publishSpork.getSignatureData());

		//publishSpork.getGridSpork().getType()
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.PUBLISH_SPORK;
	}
}
