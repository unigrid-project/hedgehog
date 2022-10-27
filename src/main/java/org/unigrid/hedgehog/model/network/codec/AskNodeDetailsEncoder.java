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
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.unigrid.hedgehog.model.network.packet.AskNodeDetails;

@Sharable
public class AskNodeDetailsEncoder extends MessageToByteEncoder<AskNodeDetails> {
	/*
	    Packet format:
	    0..............................................................63
	    PV[                         reserved                           ]
	*/
	@Override
	protected void encode(ChannelHandlerContext ctx, AskNodeDetails askNodeDetails, ByteBuf out) throws Exception {
		int flags = askNodeDetails.isProtocol() ? AskNodeDetails.Flags.PROTOCOL.getMask() : 0x00;
		flags |= askNodeDetails.isVersion() ? AskNodeDetails.Flags.VERSION.getMask() : 0x00;

		out.writeByte(flags);
		out.writeZero(7 /* 56 bits */);
	}
}
