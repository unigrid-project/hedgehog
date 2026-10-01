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
import org.unigrid.hedgehog.model.network.packet.HasFragment;
import org.unigrid.hedgehog.model.network.Packet;

@Sharable
public class HasFragmentEncoder extends AbstractMessageToByteEncoder<HasFragment>
	implements PacketEncoder<HasFragment> {

	/* [request id u64][count u16][group id 32 bytes] * count */
	@Override
	public Optional<ByteBuf> encode(final ChannelHandlerContext ctx, final HasFragment packet) throws Exception {
		final ByteBuf out = Unpooled.buffer();

		out.writeLong(packet.getRequestId());
		StorageCodecs.writeGroupIds(out, packet.getGroupIds());
		return Optional.of(out);
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.HAS_FRAGMENT;
	}
}
