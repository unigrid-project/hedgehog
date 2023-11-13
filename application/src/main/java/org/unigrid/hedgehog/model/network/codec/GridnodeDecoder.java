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
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Node.Gridnode;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.packet.GridnodePacket;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;

public class GridnodeDecoder extends AbstractReplayingDecoder<GridnodePacket> implements PacketDecoder<GridnodePacket> {

	@Inject
	private Topology topology;

	@Override
	public Optional<GridnodePacket> typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final GridnodePacket gridnodePacket = GridnodePacket.builder().build();
		final int port = in.readUnsignedShort();
		final byte gridnodeStatus = in.readByte();
		in.skipBytes(5);
		final short length = in.readShort();
		final ByteBuf data = in.readBytes(length);
		final String gridnodeKey = data.toString(StandardCharsets.UTF_8);
		final String address = ByteBufUtils.readNullTerminatedString(in);

		gridnodePacket.getNode().setAddress(new InetSocketAddress(address, port));
		gridnodePacket.getNode().setGridnode(Optional.of(Gridnode.builder().
			gridnodeKey(gridnodeKey).gridnodeStatus(Node.GridnodeStatus.get(gridnodeStatus)).build()));

		return Optional.of(gridnodePacket);
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.GRIDNODE;
	}

}
