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
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.Packet;

@Sharable
public class JoinReplyEncoder extends AbstractMessageToByteEncoder<JoinReply>
	implements PacketEncoder<JoinReply> {

	private static final String JOINED_WITHOUT_CHAIN = "A joined reply names its chain";

	/*
	 * [request id u64][status u8], and only when joined:
	 * [chain id 32 bytes][count u16]([writer 32 bytes][seq u64][hash u64][timestamp u64][mark u8]) * count
	 */
	@Override
	public Optional<ByteBuf> encode(final ChannelHandlerContext ctx, final JoinReply packet) throws Exception {
		final ByteBuf out = Unpooled.buffer();

		out.writeLong(packet.getRequestId());
		out.writeByte(packet.getStatus().ordinal());

		if (packet.getStatus() == JoinReply.Status.JOINED) {
			ChainCodecs.writeChainId(out, packet.getChainId()
				.orElseThrow(() -> new IllegalArgumentException(JOINED_WITHOUT_CHAIN)));
			ChainCodecs.writeHeads(out, packet.getHeads());
		}

		return Optional.of(out);
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.JOIN_REPLY;
	}
}
