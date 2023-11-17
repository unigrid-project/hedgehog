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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Inject;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;

public class GridnodeEncoder extends AbstractMessageToByteEncoder<PublishGridnode>
	implements PacketEncoder<PublishGridnode> {

	@Inject
	private Topology topology;

	@Override
	public Optional<ByteBuf> encode(ChannelHandlerContext ctx, PublishGridnode in) throws Exception {
		final ByteBuf out = Unpooled.buffer();
		System.out.println("encode gridnode");
		final byte[] id = in.getNode().getGridnode().get().getId().getBytes();

		out.writeShort(in.getNode().getAddress().getPort());
		out.writeByte(in.getNode().getGridnode().get().getStatus().getValue());
		out.writeZero(5);
		out.writeShort(id.length);
		out.writeBytes(id);
		ByteBufUtils.writeNullTerminatedString(in.getNode().getAddress().getAddress().getHostAddress(), out);

		return Optional.of(out);
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.GRIDNODE;
	}

}
