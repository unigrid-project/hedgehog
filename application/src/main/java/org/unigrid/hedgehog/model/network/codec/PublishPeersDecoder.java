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
import java.net.InetSocketAddress;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;

public class PublishPeersDecoder extends AbstractReplayingDecoder<PublishPeers> implements PacketDecoder<PublishPeers> {
	/*
	    Packet format:
	    0..............................................................63
	    [                << Frame Header (FrameDecoder) >>             ]
	    [ n= num peers ][                   reserved                   ]
	    [ <nodes>                                                  ...n]
	    [     port     ][                   reserved                   ]
	    [    host address                                          ...0]
	*/
	@Override
	public Optional<PublishPeers> typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final PublishPeers publishPeers = PublishPeers.builder().build();
		final int numPeers = in.readShort();

		in.skipBytes(6 /* 48 bytes */);

		for (int i = 0; i < numPeers; i++) {
			final int port = in.readUnsignedShort();

			in.skipBytes(6 /* 48 bytes */);

			final String hostAddress = ByteBufUtils.readNullTerminatedString(in);
			final InetSocketAddress socketAddress = new InetSocketAddress(hostAddress, port);
			final Node node = Node.builder().address(socketAddress).build();

			publishPeers.getNodes().add(node);
		}

		return Optional.of(publishPeers);
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.PUBLISH_PEERS;
	}
}
