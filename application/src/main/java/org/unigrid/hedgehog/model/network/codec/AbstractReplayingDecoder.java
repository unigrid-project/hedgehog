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
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.ReplayingDecoder;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.network.chunk.TypedCodec;
import org.unigrid.hedgehog.model.network.Packet;

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
				decodeWholeFrame(ctx, in, out);
				forward = false;
			}
		}

		/* Should we forward this packet on to the next decoder? */
		if (forward) {
			in.resetReaderIndex();
			in.retain();
			ctx.fireChannelRead(in);
		}
	}

	/* A frame holds one packet, no more and no less, and the decoder is given the frame alone. One that is not taken
	   whole cannot be told from the start of the next, so what is left is thrown away and the stream is closed by the
	   exception, as the bytes after it would be read as the rest of this one. */
	private void decodeWholeFrame(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		try {
			super.callDecode(ctx, in, out);

			if (in.isReadable()) {
				throw new CorruptedFrameException("A frame ends " + in.readableBytes()
					+ " bytes before its packet does");
			}
		} catch (RuntimeException ex) {
			in.skipBytes(in.readableBytes());
			throw ex;
		}
	}

	@Override
	public void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		final Optional<T> entity = typedDecode(ctx, in);
		final ByteBuf frame = internalBuffer();

		if (entity.isEmpty()) {
			/* A packet that the decoder leaves alone is left whole, not read on from the middle */
			frame.skipBytes(frame.readableBytes());
			return;
		}

		if (frame.isReadable()) {
			throw new CorruptedFrameException(frame.readableBytes() + " bytes follow the packet in its frame");
		}

		out.add(entity.get());
	}

	public abstract Optional<T> typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception;
}
