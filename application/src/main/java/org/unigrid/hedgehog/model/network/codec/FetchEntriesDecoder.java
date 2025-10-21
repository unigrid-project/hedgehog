/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class FetchEntriesDecoder extends AbstractReplayingDecoder<FetchEntries>
	implements PacketDecoder<FetchEntries> {

	private static FetchEntries.Range readRange(final ByteBuf in) {
		final WriterId writer = ChainCodecs.readWriter(in);
		final long fromSeq = in.readLong();

		return new FetchEntries.Range(writer, fromSeq, in.readLong());
	}

	@Override
	public Optional<FetchEntries> typedDecode(final ChannelHandlerContext ctx, final ByteBuf in) throws Exception {
		final ChainId chainId = ChainCodecs.readChainId(in);
		final List<FetchEntries.Range> ranges = IntStream.range(0, ChainCodecs.readCount(in,
			ChainCodecs.MAX_HEADS_PER_PACKET)).mapToObj(i -> readRange(in)).collect(Collectors.toList());

		return Optional.of(FetchEntries.builder().chainId(chainId).ranges(ranges).build());
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.FETCH_ENTRIES;
	}
}
