/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.util.Attribute;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.network.codec.chunk.TypedCodec;
import org.unigrid.hedgehog.model.network.packet.Packet;

@Slf4j
public abstract class AbstractReplayingDecoder<T extends Packet> extends ReplayingDecoder<T> {
	@Override
	protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		in.markReaderIndex();

		final Packet.Type type = ctx.channel().attr(Packet.KEY).get();
		boolean forward = true;

		if (TypedCodec.class.isAssignableFrom(getClass())) {
			final TypedCodec<Packet.Type> object = (TypedCodec<Packet.Type>) this;

			if (object.getCodecType() == type) {
				super.callDecode(ctx, in, out);
				forward = false;
			}
		}

		/* Should we forward this packet on to the next decoder? */
		if (forward) {
			in.resetReaderIndex();
			ctx.fireChannelRead(in);
		}
	}

	@Override
	public void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		final Attribute<Integer> size = ctx.channel().attr(FrameDecoder.PACKET_SIZE_KEY);
		final Optional<T> entity = typedDecode(ctx, in);

		if (entity.isPresent()) {
			out.add(entity.get());
		}

		// TODO: Verify size with PACKET_SIZE_KEY
	}

	public abstract Optional<T> typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception;
}
