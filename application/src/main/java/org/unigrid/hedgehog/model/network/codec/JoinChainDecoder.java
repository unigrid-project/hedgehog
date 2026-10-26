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
import java.util.Optional;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class JoinChainDecoder extends AbstractReplayingDecoder<JoinChain>
	implements PacketDecoder<JoinChain> {

	/* Only 0 and 1 are valid, so every request has exactly one encoding */
	private static boolean readPresence(final ByteBuf in) {
		final short flag = in.readUnsignedByte();

		if (flag > 1) {
			throw new IllegalArgumentException("Invalid presence flag " + flag);
		}

		return flag == 1;
	}

	@Override
	public Optional<JoinChain> typedDecode(final ChannelHandlerContext ctx, final ByteBuf in) throws Exception {
		final long requestId = in.readLong();
		final WriterId writer = ChainCodecs.readWriter(in);

		return Optional.of(JoinChain.builder().requestId(requestId).writer(writer)
			.chainId(readPresence(in) ? ChainCodecs.readChainId(in) : null).build());
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.JOIN_CHAIN;
	}
}
