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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.unigrid.hedgehog.jqwik.*;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;

public class BaseCodecTest<T> extends BaseMockedWeldTest {
	/* The packets of the stream that chain traffic has to itself, which the main stream refuses */
	private static final Set<Packet.Type> CHAIN_PACKETS = EnumSet.of(Packet.Type.JOIN_CHAIN, Packet.Type.JOIN_REPLY,
		Packet.Type.CHAIN_ENTRY, Packet.Type.CHAIN_HEADS, Packet.Type.FETCH_ENTRIES, Packet.Type.ENTRY_REFUSED,
		Packet.Type.LEAVE_CHAIN, Packet.Type.CHAIN_STEM);

	protected Optional<Pair<MutableInt, MutableInt>> getSizeHolder() {
		return Optional.of(Pair.of(new MutableInt(), new MutableInt()));
	}

	protected T encodeDecode(T entity, PacketEncoder<T> encoder, PacketDecoder<T> decoder,
		ChannelHandlerContext context) throws Exception {

		return encodeDecode(entity, encoder, decoder, context, Optional.empty());
	}

	protected T encodeDecode(T entity, PacketEncoder<T> encoder, PacketDecoder<T> decoder,
		ChannelHandlerContext context, Optional<Pair<MutableInt, MutableInt>> sizes) throws Exception {

		ByteBuf encodedData = Unpooled.buffer();
		encoder.encode(context, entity, encodedData);

		final FrameDecoder frameDecoder = CHAIN_PACKETS.contains(decoder.getCodecType())
			? FrameDecoder.forChainStream() : new FrameDecoder();
		encodedData = (ByteBuf) frameDecoder.decode(context, encodedData);

		final List<Object> out = new ArrayList<>();
		decoder.decode(context, encodedData, out);

		assertThat("The decoder leaves bytes of the frame", encodedData.isReadable(), is(false));

		if (sizes.isPresent()) {
			sizes.get().getLeft().setValue(encodedData.writerIndex());
			sizes.get().getRight().setValue(encodedData.readerIndex());
		}

		return (T) out.get(0);
	}
}
