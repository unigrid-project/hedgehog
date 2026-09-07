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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.EnumSet;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.network.ChainPipeline;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.PingDecoder;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.Ping;

/* A frame no stage of a pipeline takes is dropped whole, and the frame after it is read as if it had not been there.
   Without that, decoders keep what they hand on and read it again as part of the next frame. */
public class UnclaimedFrameHandlerTest {
	private static final long NANO_TIME = 42;
	private static final int HEADER = 8;
	private static final Set<Packet.Type> TAKEN_ON_THE_CHAIN_STREAM = EnumSet.of(Packet.Type.PING,
		Packet.Type.JOIN_CHAIN, Packet.Type.JOIN_REPLY, Packet.Type.CHAIN_ENTRY, Packet.Type.CHAIN_HEADS,
		Packet.Type.FETCH_ENTRIES, Packet.Type.ENTRY_REFUSED, Packet.Type.LEAVE_CHAIN);

	@Provide
	public Arbitrary<Short> typesTheChainStreamDoesNotTake() {
		return Arbitraries.oneOf(Arbitraries.of(EnumSet.complementOf(EnumSet.copyOf(TAKEN_ON_THE_CHAIN_STREAM)))
			.map(Packet.Type::getValue), Arbitraries.shorts().filter(value ->
			Packet.Type.get(value) == Packet.Type.UNDEFINED));
	}

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

	@Property
	public void dropsAFrameTheChainStreamDoesNotTakeAndReadsTheNextOne(
		@ForAll("typesTheChainStreamDoesNotTake") short type, @ForAll @IntRange(min = 1, max = 1024) int payload) {

		final EmbeddedChannel stream = new EmbeddedChannel(ChainPipeline.streamHandlers()
			.toArray(new ChannelHandler[0]));
		final ByteBuf unclaimed = frame(type, payload);

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
