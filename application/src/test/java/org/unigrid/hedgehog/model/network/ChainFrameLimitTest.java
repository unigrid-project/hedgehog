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

package org.unigrid.hedgehog.model.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.packet.Packet;

/* Any peer may open the stream chain traffic has to itself, so its frame decoder takes no frame larger than a
   batch of entries needs, and a length the frame header only claims is refused before the payload is awaited */
public class ChainFrameLimitTest {
	private static final int HEADER = ChainCodecs.FRAME_HEADER_BYTES;
	private static final int LARGEST_PAYLOAD = ChainCodecs.MAX_FRAME_BYTES - HEADER;
	private static final int LARGEST_MAIN_PAYLOAD = Network.MAX_DATA_SIZE - HEADER;
	private static final int PAYLOAD_IN_THE_SAME_READ = 1024;

	private static ByteBuf header(final int payloadBytes) {
		return Unpooled.buffer(HEADER).writeShort(FrameDecoder.MAGIC)
			.writeShort(Packet.Type.CHAIN_ENTRY.getValue()).writeInt(payloadBytes);
	}

	private static ByteBuf frame(final int claimedPayloadBytes, final int payloadBytes) {
		return Unpooled.buffer(HEADER + payloadBytes).writeBytes(header(claimedPayloadBytes)).writeZero(payloadBytes);
	}

	/* The decoder alone: no handler after it closes the channel, so the embedded channel throws what it refuses */
	private static EmbeddedChannel frames() {
		return new EmbeddedChannel(ChainPipeline.streamHandlers().get(0));
	}

	/* The whole stream, with a handler right after the decoder that notes what the decoder throws */
	private static EmbeddedChannel stream(final List<Throwable> thrown) {
		final EmbeddedChannel channel = new EmbeddedChannel(ChainPipeline.streamHandlers()
			.toArray(new ChannelHandler[0]));

		channel.pipeline().addAfter(channel.pipeline().firstContext().name(), "thrown",
			new ChannelInboundHandlerAdapter() {
				@Override public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
					thrown.add(cause);
					ctx.fireExceptionCaught(cause);
				}
			});

		return channel;
	}

	private static int readFrameBytes(final EmbeddedChannel channel) {
		final ByteBuf frame = channel.readInbound();

		try {
			return frame.readableBytes();
		} finally {
			frame.release();
		}
	}

	private static void assertClosedWithNothingKept(final EmbeddedChannel channel, final List<Throwable> thrown,
		final ByteBuf read) {

		assertThat(thrown, contains(instanceOf(TooLongFrameException.class)));
		assertThat(channel.isOpen(), is(false));
		assertThat(read.refCnt(), is(0));
		assertThat(channel.readInbound(), is(nullValue()));
		assertThat(channel.readOutbound(), is(nullValue()));
		assertThat(channel.pipeline().first(), is(nullValue()));
	}

	@Example
	public void takesAFrameOfExactlyTheLimit() {
		final EmbeddedChannel channel = frames();
		final ByteBuf frame = header(LARGEST_PAYLOAD).writeZero(LARGEST_PAYLOAD);

		assertThat(channel.writeInbound(frame), is(true));
		assertThat(readFrameBytes(channel), is(LARGEST_PAYLOAD));
	}

	@Example
	public void waitsForThePayloadOfAHeaderThatClaimsExactlyTheLimit() {
		final EmbeddedChannel channel = frames();

		assertThat(channel.writeInbound(header(LARGEST_PAYLOAD)), is(false));
		assertThat(channel.isOpen(), is(true));
		assertThat(channel.writeInbound(Unpooled.buffer(LARGEST_PAYLOAD).writeZero(LARGEST_PAYLOAD)), is(true));
		assertThat(readFrameBytes(channel), is(LARGEST_PAYLOAD));
	}

	@Example
	public void refusesAFrameOneByteOverTheLimitBeforeItsPayloadArrives() {
		final EmbeddedChannel channel = frames();
		final ByteBuf claim = header(LARGEST_PAYLOAD + 1);

		assertThrows(TooLongFrameException.class, () -> channel.writeInbound(claim));
		assertThat(claim.refCnt(), is(0));
		assertThat(channel.readInbound(), is(nullValue()));
	}

	@Example
	public void closesTheStreamOnTheHeaderOfAFrameOneByteOverTheLimit() {
		final List<Throwable> thrown = new CopyOnWriteArrayList<>();
		final EmbeddedChannel channel = stream(thrown);
		final ByteBuf claim = header(LARGEST_PAYLOAD + 1);

		channel.writeInbound(claim);
		assertClosedWithNothingKept(channel, thrown, claim);
	}

	@Example
	public void discardsThePayloadThatArrivesWithARefusedHeader() {
		final List<Throwable> thrown = new CopyOnWriteArrayList<>();
		final EmbeddedChannel channel = stream(thrown);
		final ByteBuf read = frame(LARGEST_PAYLOAD + 1, PAYLOAD_IN_THE_SAME_READ);

		channel.writeInbound(read);
		assertClosedWithNothingKept(channel, thrown, read);

		final ByteBuf alone = frame(LARGEST_PAYLOAD + 1, PAYLOAD_IN_THE_SAME_READ);

		assertThrows(TooLongFrameException.class, () -> frames().writeInbound(alone));
		assertThat(alone.refCnt(), is(0));
	}

	@Property(tries = 20)
	public void closesTheStreamOnAHeaderThatClaimsAHugeFrameWithoutKeepingIt(
		@ForAll @IntRange(min = LARGEST_PAYLOAD + 1) int claimed) {

		final List<Throwable> thrown = new CopyOnWriteArrayList<>();
		final EmbeddedChannel channel = stream(thrown);
		final ByteBuf claim = header(claimed);

		channel.writeInbound(claim);
		assertClosedWithNothingKept(channel, thrown, claim);
	}

	/* Reads end wherever the bytes on the way do, so the header of a frame may come in two of them */
	@Property
	public void takesAFrameWhoseHeaderArrivesInTwoReads(@ForAll @IntRange(min = 1, max = HEADER - 1) int split) {
		final EmbeddedChannel channel = frames();
		final ByteBuf frame = frame(PAYLOAD_IN_THE_SAME_READ, PAYLOAD_IN_THE_SAME_READ);

		assertThat(channel.writeInbound(frame.retainedSlice(0, split)), is(false));
		assertThat(channel.writeInbound(frame.slice(split, frame.readableBytes() - split)), is(true));
		assertThat(readFrameBytes(channel), is(PAYLOAD_IN_THE_SAME_READ));
	}

	@Example
	public void theMainStreamTakesTheFrameTheChainStreamRefuses() {
		final EmbeddedChannel main = new EmbeddedChannel(new FrameDecoder());
		final ByteBuf refused = frame(LARGEST_PAYLOAD + 1, LARGEST_PAYLOAD + 1);

		assertThat(main.writeInbound(frame(LARGEST_PAYLOAD + 1, LARGEST_PAYLOAD + 1)), is(true));
		assertThat(readFrameBytes(main), is(LARGEST_PAYLOAD + 1));
		assertThrows(TooLongFrameException.class, () -> frames().writeInbound(refused));
		assertThat(refused.refCnt(), is(0));
	}

	@Example
	public void theMainStreamKeepsItsOwnLimit() {
		final EmbeddedChannel main = new EmbeddedChannel(new FrameDecoder());

		assertThat(main.writeInbound(header(LARGEST_MAIN_PAYLOAD)), is(false));
		assertThat(main.isOpen(), is(true));
		assertThat(main.finishAndReleaseAll(), is(false));
		assertThrows(TooLongFrameException.class, () -> new EmbeddedChannel(new FrameDecoder())
			.writeInbound(header(LARGEST_MAIN_PAYLOAD + 1)));
	}
}
