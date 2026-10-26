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
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.Packet;

@Sharable
public class FetchEntriesEncoder extends AbstractMessageToByteEncoder<FetchEntries>
	implements PacketEncoder<FetchEntries> {

	/*
	 * [chain id 32 bytes][count u16]([writer 32 bytes][from seq u64][to seq u64]) * count, bounded like the heads
	 * because a fetch asks for at most one range per head it compared
	 */
	@Override
	public Optional<ByteBuf> encode(final ChannelHandlerContext ctx, final FetchEntries packet) throws Exception {
		final ByteBuf out = Unpooled.buffer();

		ChainCodecs.writeChainId(out, packet.getChainId());
		ChainCodecs.writeCount(out, packet.getRanges().size(), ChainCodecs.MAX_HEADS_PER_PACKET);

		for (final FetchEntries.Range range : packet.getRanges()) {
			ChainCodecs.writeWriter(out, range.writer());
			out.writeLong(range.fromSeq()).writeLong(range.toSeq());
		}

		return Optional.of(out);
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.FETCH_ENTRIES;
	}
}
