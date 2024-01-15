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
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.crypto.NetworkIdentifier;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;

@Slf4j
public class GridnodeDecoder extends AbstractReplayingDecoder<PublishGridnode> implements PacketDecoder<PublishGridnode> {

	@Inject
	private Topology topology;

	@Inject
	private NetworkIdentifier id;

	@Override
	public Optional<PublishGridnode> typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {

		final PublishGridnode gridnodePacket = PublishGridnode.builder().build();
		final byte gridnodeStatus = in.readByte();
		in.skipBytes(5);
		final short length = in.readShort();
		final ByteBuf data = in.readBytes(length);
		final String gridnodeId = data.toString(StandardCharsets.UTF_8);
		final String hostName = ByteBufUtils.readNullTerminatedString(in);
		log.atDebug().log("decode gridnode");

		gridnodePacket.setGridnode(Gridnode.builder().hostName(hostName)
			.id(gridnodeId).status(Gridnode.Status.get(gridnodeStatus)).build());

		return Optional.of(gridnodePacket);
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.GRIDNODE;
	}

}
