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

package org.unigrid.hedgehog.transport.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.Attribute;
import java.util.Objects;
import org.unigrid.hedgehog.model.network.packet.Ping;

/* Anything that arrives on a stream shows that the peer is there, so it starts the count of missed pings again
   (PingSchedule), as an answer does. It reads ahead of every decoder: an answer can wait behind a frame of hundreds of
   megabytes on the same stream, whose pieces count while it streams in.

   What a peer reads shows nothing of that kind, as a stream takes what is written to it until it is full, so the bytes
   that left the stream are counted here as well, behind every encoder: by them the schedule tells a peer that reads
   slowly from one that reads nothing, once the stream takes no more. A frame is handed on in pieces, each counted when
   the whole of it left: counted as one, a frame of megabytes would show nothing of a peer that is reading it until
   the last of it is gone. A stream that takes more again has been read, which starts the count of the periods
   through which it took none again. */
@Sharable
public class PeerActivityHandler extends ChannelDuplexHandler {
	public static final int PIECE_BYTES = 16 * 1024;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		ctx.channel().attr(Ping.MISSED_PINGS_KEY).set(0);
		ctx.fireChannelRead(msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof ByteBuf frame) {
			while (frame.readableBytes() > PIECE_BYTES) {
				writeCounted(ctx, frame.readRetainedSlice(PIECE_BYTES), ctx.newPromise());
			}

			writeCounted(ctx, frame, promise.unvoid());
		} else {
			ctx.write(msg, promise);
		}
	}

	private static void writeCounted(ChannelHandlerContext ctx, ByteBuf piece, ChannelPromise promise) {
		final int bytes = piece.readableBytes();

		ctx.write(piece, promise).addListener(written -> {
			if (written.isSuccess()) {
				final Attribute<Long> taken = ctx.channel().attr(Ping.TAKEN_BYTES_KEY);

				taken.set(Objects.requireNonNullElse(taken.get(), 0L) + bytes);
			}
		});
	}

	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) {
		if (ctx.channel().isWritable()) {
			ctx.channel().attr(Ping.UNWRITABLE_PERIODS_KEY).set(null);
		}

		ctx.fireChannelWritabilityChanged();
	}
}
