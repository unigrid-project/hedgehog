/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

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
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;

@Slf4j
public class GridnodeEncoder extends AbstractMessageToByteEncoder<PublishGridnode>
	implements PacketEncoder<PublishGridnode> {

	@Override
	public Optional<ByteBuf> encode(ChannelHandlerContext ctx, PublishGridnode in) throws Exception {
		final Gridnode gridnode = in.getGridnode();
		final ByteBuf out = Unpooled.buffer();

		log.atDebug().log("encode gridnode");
		out.writeByte(gridnode.getStatus().getValue());
		out.writeLong(gridnode.getTimestamp());
		writeField(out, gridnode.getId().getBytes(StandardCharsets.UTF_8));
		writeField(out, gridnode.getHostName().getBytes(StandardCharsets.UTF_8));
		writeField(out, gridnode.getSignature());
		return Optional.of(out);
	}

	private static void writeField(ByteBuf out, byte[] field) {
		out.writeShort(field.length);
		out.writeBytes(field);
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.GRIDNODE;
	}
}
