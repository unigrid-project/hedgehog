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
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.network.packet.Ping;

/* Anything that arrives on a stream shows that the peer is there, so it starts the count of missed pings again
   (PingSchedule), as an answer does. It reads ahead of every decoder: an answer can wait behind a frame of hundreds of
   megabytes on the same stream, whose pieces count while it streams in.

   What a peer reads shows nothing of that kind, as a stream takes what is written to it until it is full, so the bytes
   that left the stream are counted here as well, behind every encoder: by them the schedule tells a peer that reads
   slowly from one that reads nothing, once the stream takes no more. A frame is handed on in pieces, each counted when
   the whole of it left: counted as one, a frame of megabytes would show nothing of a peer that is reading it until
   the last of it is gone. The piece that is leaving counts by as much of it as is out, which the stream tells by the
   reader index of the piece, moved on as it sends: by whole pieces alone, a peer that reads less than a piece in a
   period would show as one that reads nothing in one period and a whole piece in the next. A stream that takes more
   again has been read, so its peer no longer owes what the periods before were short of. */
@Sharable
public class PeerActivityHandler extends ChannelDuplexHandler {
	public static final int PIECE_BYTES = 16 * 1024;
	private static final AttributeKey<Deque<Piece>> LEAVING_KEY = AttributeKey.valueOf("LEAVING_PIECES");

	/* A piece that was handed on and is not out whole, held on to here for as long, so that what it tells of itself
	   is its own: let go of by the stream alone, its buffer could be another piece by the time that it is asked */
	@RequiredArgsConstructor
	private static final class Piece {
		private final ByteBuf buffer;
		private final int bytes;

		private int out() {
			return bytes - buffer.readableBytes();
		}
	}

	/* All that left the stream since it opened, to be asked on the event loop of the stream */
	public static long takenOf(Channel stream) {
		final long whole = Objects.requireNonNullElse(stream.attr(Ping.TAKEN_BYTES_KEY).get(), 0L);
		final Optional<Piece> leaving = Optional.ofNullable(stream.attr(LEAVING_KEY).get()).map(Deque::peek);

		return whole + leaving.map(Piece::out).orElse(0);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		ctx.channel().attr(Ping.MISSED_PINGS_KEY).set(0);
		ctx.fireChannelRead(msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof ByteBuf buffer) {
			final ByteBuf frame = direct(ctx, buffer);

			while (frame.readableBytes() > PIECE_BYTES) {
				writeCounted(ctx, frame.readRetainedSlice(PIECE_BYTES), ctx.newPromise());
			}

			writeCounted(ctx, frame, promise.unvoid());
		} else {
			ctx.write(msg, promise);
		}
	}

	/* A stream sends a copy of a buffer that is not direct and lets go of the buffer at once, so nothing of what
	   leaves would show on a piece of it: the copy is made here, and its pieces are the ones that the stream sends */
	private static ByteBuf direct(ChannelHandlerContext ctx, ByteBuf frame) {
		if (frame.isDirect()) {
			return frame;
		}

		try {
			return ctx.alloc().directBuffer(frame.readableBytes()).writeBytes(frame);
		} finally {
			frame.release();
		}
	}

	private static void writeCounted(ChannelHandlerContext ctx, ByteBuf buffer, ChannelPromise promise) {
		final Deque<Piece> leaving = leavingOf(ctx.channel());
		final Piece piece = new Piece(buffer.retain(), buffer.readableBytes());

		/* Before the write, which a stream that takes the whole piece at once ends before it returns */
		leaving.add(piece);

		ctx.write(buffer, promise).addListener(written -> {
			leaving.remove(piece);
			buffer.release();

			if (written.isSuccess()) {
				final Attribute<Long> taken = ctx.channel().attr(Ping.TAKEN_BYTES_KEY);

				taken.set(Objects.requireNonNullElse(taken.get(), 0L) + piece.bytes);
			}
		});
	}

	private static Deque<Piece> leavingOf(Channel stream) {
		final Attribute<Deque<Piece>> leaving = stream.attr(LEAVING_KEY);

		if (Objects.isNull(leaving.get())) {
			leaving.set(new ArrayDeque<>());
		}

		return leaving.get();
	}

	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) {
		if (ctx.channel().isWritable()) {
			ctx.channel().attr(Ping.DEBT_KEY).set(null);
		}

		ctx.fireChannelWritabilityChanged();
	}
}
