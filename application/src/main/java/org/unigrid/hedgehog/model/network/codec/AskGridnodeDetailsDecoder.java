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
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Inject;
import java.util.Optional;
import org.unigrid.hedgehog.model.crypto.NetworkIdentifier;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.AskGridnodeDetails;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.packet.Packet;

public class AskGridnodeDetailsDecoder extends AbstractReplayingDecoder<AskGridnodeDetails>
	implements PacketDecoder<AskGridnodeDetails> {

	@Inject
	private Topology topology;

	@Inject
	private NetworkIdentifier id;

	@Override
	public Optional<AskGridnodeDetails> typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		AskGridnodeDetails gridnodeDetails = AskGridnodeDetails.builder().message(in.readShort()).build();
		System.out.println("decoding gridnode-get");
		return Optional.of(gridnodeDetails);
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.ASK_GRIDNODE_DETAILS;
	}

}
