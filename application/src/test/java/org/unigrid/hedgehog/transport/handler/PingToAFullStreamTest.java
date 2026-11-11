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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Objects;
import java.util.stream.LongStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.transport.initializer.ChainPipeline;
import org.unigrid.hedgehog.transport.initializer.PeerPipeline;
import org.unigrid.hedgehog.transport.initializer.RegisterQuicChannelInitializer.Type;

/* A peer sends as many pings as it likes, and a stream holds whatever is written to it once it takes no more, without
   a bound. A ping that arrives on such a stream is therefore left unanswered, as the answers to a peer that reads too
   little would pile up in memory at the rate of its pings, and the ping that arrives once the stream takes more is
   answered as any. The stream is the node's own pipeline, and nothing leaves its outbound side until the peer is let
   to read, as a peer's receive window that stays shut leaves it. */
public class PingToAFullStreamTest {
	private static final int PINGS = 10000;
	private static final int BODY_BYTES = 2 * Long.BYTES;
	private static final int RESERVED_BYTES = 7;

	/* A stream whose peer takes nothing off it until it is let to: what is written waits in its outbound buffer */
	private static final class Unread extends EmbeddedChannel {
		private ByteBuf read;

		private Unread(List<ChannelHandler> handlers) {
			super(new EmbeddedChannel(), DefaultChannelId.newInstance(), true, false,
				handlers.toArray(new ChannelHandler[0]));
		}

		@Override
		protected void doWrite(ChannelOutboundBuffer in) {
			for (Object next = in.current(); Objects.nonNull(read) && next instanceof ByteBuf waiting;
				next = in.current()) {

				read.writeBytes(waiting, waiting.readerIndex(), waiting.readableBytes());
				in.remove();
			}
		}

		/* The peer reads all there is, which is what comes back */
		private ByteBuf peerReadsAll() {
			final ByteBuf all = Unpooled.buffer();

			read = all;
			flush();
			read = null;
			return all;
		}

		private long pending() {
			final ChannelOutboundBuffer waiting = unsafe().outboundBuffer();

			return Objects.isNull(waiting) ? 0 : waiting.totalPendingWriteBytes();
		}
	}

	/* A ping as it is on the wire: the header of its frame, the time of its sender, and whether it is an answer */
	private static ByteBuf ping(long nanoTime, boolean response) {
		return Unpooled.buffer().writeShort(FrameDecoder.MAGIC).writeShort(Packet.Type.PING.getValue())
			.writeInt(BODY_BYTES).writeLong(nanoTime).writeByte(response ? 1 : 0).writeZero(RESERVED_BYTES);
	}

	/* The pings are told apart by their time, so the answer that the peer reads at the end is one to the ping that
	   came once the stream took more, and what filled the stream is all that the peer reads before it: an answer
	   that was left out is not made up for later */
	private static void assertThatPingsAreAnsweredOnlyWhileItTakesMore(Unread stream) {
		final int filling = stream.config().getWriteBufferHighWaterMark();

		try {
			stream.writeAndFlush(Unpooled.wrappedBuffer(new byte[filling]));
			assertThat(stream.isWritable(), is(false));

			final long held = stream.pending();

			LongStream.rangeClosed(1, PINGS).forEach(nanoTime -> stream.writeInbound(ping(nanoTime, false)));

			assertThat("bytes that the answers to " + PINGS + " pings added to a stream that takes no more",
				stream.pending() - held, is(0L));
			assertThat(stream.isOpen(), is(true));
			assertThat(stream.peerReadsAll().readableBytes(), is(filling));
			assertThat(stream.isWritable(), is(true));

			stream.writeInbound(ping(PINGS + 1, false));

			assertThat("what the peer reads after a ping to a stream that takes more again",
				ByteBufUtil.hexDump(stream.peerReadsAll()), is(ByteBufUtil.hexDump(ping(PINGS + 1, true))));
			assertThat(stream.isOpen(), is(true));
		} finally {
			stream.finishAndReleaseAll();
		}
	}

	/* The main stream of a node that runs neither the ledger nor the preview */
	@Example
	public void answersAPingOnAMainStreamOnlyWhileTheStreamTakesMore() {
		assertThatPingsAreAnsweredOnlyWhileItTakesMore(
			new Unread(PeerPipeline.handlers(Type.SERVER, false, false)));
	}

	/* The stream that chain traffic has to itself answers pings by the same handler */
	@Example
	public void answersAPingOnAChainStreamOnlyWhileTheStreamTakesMore() {
		assertThatPingsAreAnsweredOnlyWhileItTakesMore(new Unread(ChainPipeline.streamHandlers()));
	}
}
