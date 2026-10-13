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
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
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
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class ChainHeadsIntegrityTest extends BaseCodecTest<ChainHeads> {
	private static WriterId writerOf(final int index) {
		return new WriterId(ByteBuffer.allocate(WriterId.SIZE).putInt(index).array());
	}

	@Provide
	public Arbitrary<ChainId> chainIds() {
		return ChainArbitraries.chainIds();
	}

	@Provide
	public Arbitrary<Map<WriterId, Head>> heads() {
		return ChainArbitraries.heads();
	}

	@Property(tries = 30)
	public void chainHeads(@ForAll("chainIds") ChainId chainId, @ForAll("heads") Map<WriterId, Head> heads,
		@Mocked ChannelHandlerContext context) throws Exception {

		final ChainHeads packet = ChainHeads.builder().chainId(chainId).heads(heads).build();

		assertThat(encodeDecode(packet, new ChainHeadsEncoder(), new ChainHeadsDecoder(), context), equalTo(packet));
	}

	@Property(tries = 5)
	public void encoderRefusesTooManyHeads(@ForAll("chainIds") ChainId chainId,
		@ForAll @IntRange(min = ChainCodecs.MAX_HEADS_PER_PACKET + 1, max = 2 * ChainCodecs.MAX_HEADS_PER_PACKET)
		int count, @Mocked ChannelHandlerContext context) {

		final Map<WriterId, Head> heads = IntStream.range(0, count).boxed()
			.collect(Collectors.toMap(ChainHeadsIntegrityTest::writerOf, index -> new Head(index, index)));
		final ChainHeads packet = ChainHeads.builder().chainId(chainId).heads(heads).build();

		assertThrows(IllegalArgumentException.class, () -> new ChainHeadsEncoder().encode(context, packet));
	}

	@Property(tries = 30)
	public void decoderRefusesTooManyHeads(
		@ForAll @IntRange(min = ChainCodecs.MAX_HEADS_PER_PACKET + 1, max = 0xFFFF) int count,
		@Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeZero(ChainId.SIZE).writeShort(count);

		assertThrows(IllegalArgumentException.class, () -> new ChainHeadsDecoder().typedDecode(context, in));
	}

	@Property(tries = 30)
	public void decoderRefusesANegativeHeadSequenceNumber(@ForAll @Size(WriterId.SIZE) byte[] writer,
		@ForAll @Negative long seq, @ForAll long hash, @Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeZero(ChainId.SIZE).writeShort(1).writeBytes(writer)
			.writeLong(seq).writeLong(hash);

		assertThrows(IllegalArgumentException.class, () -> new ChainHeadsDecoder().typedDecode(context, in));
	}

	@Property(tries = 5)
	public void decoderTakesAHeadAtTheLastSequenceNumberAndLeavesItToTheHost(
		@ForAll("chainIds") ChainId chainId, @ForAll long hash, @Mocked ChannelHandlerContext context)
		throws Exception {

		final ChainHeads packet = ChainHeads.builder().chainId(chainId)
			.heads(Map.of(writerOf(1), new Head(Long.MAX_VALUE, hash))).build();

		assertThat(encodeDecode(packet, new ChainHeadsEncoder(), new ChainHeadsDecoder(), context), equalTo(packet));
	}

	@Example
	public void carriesItsPacketType() {
		assertThat(ChainHeads.builder().build().getType(), equalTo(Packet.Type.CHAIN_HEADS));
		assertThat(Packet.Type.CHAIN_HEADS.getValue(), equalTo((short) 4030));
		assertThat(new ChainHeadsEncoder().getCodecType(), equalTo(Packet.Type.CHAIN_HEADS));
		assertThat(new ChainHeadsDecoder().getCodecType(), equalTo(Packet.Type.CHAIN_HEADS));
	}

	@Example
	public void neverHoldsNullHeads() {
		assertThat(ChainHeads.builder().build().getHeads(), equalTo(Map.of()));
	}
}
