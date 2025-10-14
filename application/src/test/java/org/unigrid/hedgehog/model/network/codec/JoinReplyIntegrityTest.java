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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import java.util.Map;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Negative;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.JoinReply.Status;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class JoinReplyIntegrityTest extends BaseCodecTest<JoinReply> {
	@Provide
	public Arbitrary<ChainId> chainIds() {
		return ChainArbitraries.chainIds();
	}

	@Provide
	public Arbitrary<Map<WriterId, Head>> heads() {
		return ChainArbitraries.heads();
	}

	@Property(tries = 30)
	public void joinedReply(@ForAll long requestId, @ForAll("chainIds") ChainId chainId,
		@ForAll("heads") Map<WriterId, Head> heads, @Mocked ChannelHandlerContext context) throws Exception {

		final JoinReply packet = JoinReply.builder().requestId(requestId).status(Status.JOINED).chainId(chainId)
			.heads(heads).build();

		assertThat(encodeDecode(packet, new JoinReplyEncoder(), new JoinReplyDecoder(), context), equalTo(packet));
	}

	@Property(tries = 30)
	public void refusedReply(@ForAll long requestId, @ForAll Status status, @Mocked ChannelHandlerContext context)
		throws Exception {

		Assume.that(status != Status.JOINED);

		final JoinReply packet = JoinReply.builder().requestId(requestId).status(status).build();

		assertThat(encodeDecode(packet, new JoinReplyEncoder(), new JoinReplyDecoder(), context), equalTo(packet));
	}

	@Property(tries = 10)
	public void encoderRefusesAJoinedReplyWithoutItsChain(@ForAll long requestId,
		@Mocked ChannelHandlerContext context) {

		final JoinReply packet = JoinReply.builder().requestId(requestId).status(Status.JOINED).build();

		assertThrows(IllegalArgumentException.class, () -> new JoinReplyEncoder().encode(context, packet));
	}

	@Property(tries = 30)
	public void decoderRefusesTooManyHeads(@ForAll long requestId,
		@ForAll @IntRange(min = ChainCodecs.MAX_HEADS_PER_PACKET + 1, max = 0xFFFF) int count,
		@Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeLong(requestId).writeByte(Status.JOINED.ordinal())
			.writeZero(ChainId.SIZE).writeShort(count);

		assertThrows(IllegalArgumentException.class, () -> new JoinReplyDecoder().typedDecode(context, in));
	}

	@Property(tries = 30)
	public void decoderRefusesANegativeHeadSequenceNumber(@ForAll long requestId,
		@ForAll @Size(WriterId.SIZE) byte[] writer, @ForAll @Negative long seq, @ForAll long hash,
		@Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeLong(requestId).writeByte(Status.JOINED.ordinal())
			.writeZero(ChainId.SIZE).writeShort(1).writeBytes(writer).writeLong(seq).writeLong(hash);

		assertThrows(IllegalArgumentException.class, () -> new JoinReplyDecoder().typedDecode(context, in));
	}

	@Property(tries = 5)
	public void decoderTakesAHeadAtTheLastSequenceNumberAndLeavesItToTheHost(@ForAll long requestId,
		@ForAll("chainIds") ChainId chainId, @ForAll @Size(WriterId.SIZE) byte[] writer, @ForAll long hash,
		@Mocked ChannelHandlerContext context) throws Exception {

		final Map<WriterId, Head> heads = Map.of(new WriterId(writer), new Head(Long.MAX_VALUE, hash));
		final JoinReply packet = JoinReply.builder().requestId(requestId).status(Status.JOINED).chainId(chainId)
			.heads(heads).build();

		assertThat(encodeDecode(packet, new JoinReplyEncoder(), new JoinReplyDecoder(), context), equalTo(packet));
	}

	@Property
	public void statusMapsAnyIntToAStatus(@ForAll int ordinal) {
		final Status[] statuses = Status.values();
		final boolean known = ordinal >= 0 && ordinal < statuses.length;

		assertThat(Status.of(ordinal), equalTo(known ? statuses[ordinal] : Status.ERROR));
	}

	@Property(tries = 30)
	public void decoderMapsUnknownStatusBytesToAnError(@ForAll long requestId,
		@ForAll @IntRange(max = 0xFF) int status, @Mocked ChannelHandlerContext context) throws Exception {

		Assume.that(status >= Status.values().length);

		final ByteBuf in = Unpooled.buffer().writeLong(requestId).writeByte(status);

		assertThat(new JoinReplyDecoder().typedDecode(context, in).get().getStatus(), equalTo(Status.ERROR));
	}

	@Example
	public void carriesItsPacketType() {
		assertThat(JoinReply.builder().build().getType(), equalTo(Packet.Type.JOIN_REPLY));
		assertThat(Packet.Type.JOIN_REPLY.getValue(), equalTo((short) 4010));
	}

	@Example
	public void statusMapsTheBoundaryOrdinals(@Mocked ChannelHandlerContext context) throws Exception {
		final int unknown = Status.values().length;
		final ByteBuf in = Unpooled.buffer().writeLong(0).writeByte(unknown);

		assertThat(Status.of(-1), equalTo(Status.ERROR));
		assertThat(Status.of(0), equalTo(Status.JOINED));
		assertThat(Status.of(unknown), equalTo(Status.ERROR));
		assertThat(new JoinReplyDecoder().typedDecode(context, in).get().getStatus(), equalTo(Status.ERROR));
	}

	@Example
	public void keepsTheStatusBytesOfDeployedNodes() {
		assertThat(Status.of(0), equalTo(Status.JOINED));
		assertThat(Status.of(1), equalTo(Status.FULL));
		assertThat(Status.of(2), equalTo(Status.DISABLED));
		assertThat(Status.of(3), equalTo(Status.UNKNOWN_CHAIN));
		assertThat(Status.of(4), equalTo(Status.ERROR));
		assertThat(Status.values().length, equalTo(5));
	}
}
