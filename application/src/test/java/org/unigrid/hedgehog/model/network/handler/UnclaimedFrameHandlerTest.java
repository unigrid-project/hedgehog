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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.PingDecoder;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.Ping;

/* A frame no stage of a pipeline takes is dropped whole, and the frame after it is read as if it had not been there.
   Without that, decoders keep what they hand on and read it again as part of the next frame. */
public class UnclaimedFrameHandlerTest {
	private static final long NANO_TIME = 42;
	private static final int HEADER = 8;
	private static final int LEAVE_CHAIN_PAYLOAD = 64;

	private static ByteBuf frame(final short type, final int payloadBytes) {
		return Unpooled.buffer(HEADER + payloadBytes).writeShort(FrameDecoder.MAGIC).writeShort(type)
			.writeInt(payloadBytes).writeBytes(Unpooled.buffer(payloadBytes).writerIndex(payloadBytes)
			.setByte(0, 0xff));
	}

	private static ByteBuf ping() {
		final EmbeddedChannel encoder = new EmbeddedChannel(new PingEncoder());

		encoder.writeOutbound(Ping.builder().nanoTime(NANO_TIME).build());
		return encoder.readOutbound();
	}

	private static void assertThePingIsAnsweredAlone(final EmbeddedChannel channel) {
		final ByteBuf answer = channel.readOutbound();

		assertThat(answer.skipBytes(HEADER).readLong(), is(NANO_TIME));
		assertThat(answer.readByte(), is((byte) 1));
		assertThat(channel.readOutbound(), is(nullValue()));
		answer.release();
	}

	/* A frame of a type the stream takes in size, but of which no stage reads this kind: the chain stream refuses every
	   other type before it gets here (FrameDecoderTest) */
	@Example
	public void dropsAChainFrameThatNoStageClaimsAndReadsTheNextOne() {
		final EmbeddedChannel stream = new EmbeddedChannel(FrameDecoder.forChainStream(), new PingEncoder(),
			new PingDecoder(), new PingChannelHandler(), new UnclaimedFrameHandler());
		final ByteBuf unclaimed = frame(Packet.Type.LEAVE_CHAIN.getValue(), LEAVE_CHAIN_PAYLOAD);

		assertThat(stream.writeInbound(unclaimed), is(false));
		assertThat(unclaimed.refCnt(), is(0));
		assertThat(stream.writeInbound(ping()), is(false));
		assertThePingIsAnsweredAlone(stream);
		assertThat(stream.isOpen(), is(true));
	}

	@Example
	public void leavesWhatIsNotAFrameToTheStagesAfterIt() {
		final EmbeddedChannel channel = new EmbeddedChannel(new UnclaimedFrameHandler());
		final Ping ping = Ping.builder().build();

		assertThat(channel.writeInbound(ping), is(true));
		assertThat(channel.readInbound(), is(ping));
	}

	@Example
	public void dropsAFrameThatOnlyPassesDecodersOfOtherTypes() {
		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new PingEncoder(), new PingDecoder(),
			new PingChannelHandler(), new UnclaimedFrameHandler());
		final ByteBuf unclaimed = frame(Packet.Type.HELLO.getValue(), 2 * Long.BYTES);

		channel.writeInbound(unclaimed);
		assertThat(unclaimed.refCnt(), is(0));
		channel.writeInbound(ping());
		assertThePingIsAnsweredAlone(channel);
	}
}
