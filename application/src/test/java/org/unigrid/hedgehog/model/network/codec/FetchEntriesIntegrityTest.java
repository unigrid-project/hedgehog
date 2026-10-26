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
import java.util.Collections;
import java.util.List;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.FetchEntries.Range;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class FetchEntriesIntegrityTest extends BaseCodecTest<FetchEntries> {
	@Provide
	public Arbitrary<ChainId> chainIds() {
		return ChainArbitraries.chainIds();
	}

	@Provide
	public Arbitrary<Range> ranges() {
		final Arbitrary<Long> halfway = Arbitraries.longs().between(0, Long.MAX_VALUE / 2);

		return Combinators.combine(ChainArbitraries.writers(), halfway, halfway)
			.as((writer, fromSeq, width) -> new Range(writer, fromSeq, fromSeq + width));
	}

	@Provide
	public Arbitrary<List<Range>> rangeLists() {
		return ranges().list().ofMaxSize(40);
	}

	@Property(tries = 30)
	public void fetchEntries(@ForAll("chainIds") ChainId chainId, @ForAll("rangeLists") List<Range> ranges,
		@Mocked ChannelHandlerContext context) throws Exception {

		final FetchEntries packet = FetchEntries.builder().chainId(chainId).ranges(ranges).build();

		assertThat(encodeDecode(packet, new FetchEntriesEncoder(), new FetchEntriesDecoder(), context),
			equalTo(packet));
	}

	@Property(tries = 3)
	public void takesExactlyTheMostRangesOfAPacket(@ForAll("chainIds") ChainId chainId, @ForAll("ranges") Range range,
		@Mocked ChannelHandlerContext context) throws Exception {

		final FetchEntries packet = FetchEntries.builder().chainId(chainId)
			.ranges(Collections.nCopies(ChainCodecs.MAX_HEADS_PER_PACKET, range)).build();

		assertThat(encodeDecode(packet, new FetchEntriesEncoder(), new FetchEntriesDecoder(), context),
			equalTo(packet));
	}

	@Property(tries = 10)
	public void encoderRefusesTooManyRanges(@ForAll("chainIds") ChainId chainId, @ForAll("ranges") Range range,
		@ForAll @IntRange(min = ChainCodecs.MAX_HEADS_PER_PACKET + 1, max = 0xFFFF) int count,
		@Mocked ChannelHandlerContext context) {

		final FetchEntries packet = FetchEntries.builder().chainId(chainId).ranges(Collections.nCopies(count, range))
			.build();

		assertThrows(IllegalArgumentException.class, () -> new FetchEntriesEncoder().encode(context, packet));
	}

	@Property(tries = 30)
	public void decoderRefusesTooManyRanges(
		@ForAll @IntRange(min = ChainCodecs.MAX_HEADS_PER_PACKET + 1, max = 0xFFFF) int count,
		@Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeZero(ChainId.SIZE).writeShort(count);

		assertThrows(IllegalArgumentException.class, () -> new FetchEntriesDecoder().typedDecode(context, in));
	}

	@Property(tries = 30)
	public void decoderRefusesNegativeOrReversedRanges(@ForAll long fromSeq, @ForAll long toSeq,
		@Mocked ChannelHandlerContext context) {

		Assume.that(fromSeq < 0 || toSeq < fromSeq);

		final ByteBuf in = Unpooled.buffer().writeZero(ChainId.SIZE).writeShort(1).writeZero(WriterId.SIZE)
			.writeLong(fromSeq).writeLong(toSeq);

		assertThrows(IllegalArgumentException.class, () -> new FetchEntriesDecoder().typedDecode(context, in));
	}

	@Example
	public void carriesItsPacketType() {
		assertThat(FetchEntries.builder().build().getType(), equalTo(Packet.Type.FETCH_ENTRIES));
		assertThat(Packet.Type.FETCH_ENTRIES.getValue(), equalTo((short) 5040));
		assertThat(new FetchEntriesEncoder().getCodecType(), equalTo(Packet.Type.FETCH_ENTRIES));
		assertThat(new FetchEntriesDecoder().getCodecType(), equalTo(Packet.Type.FETCH_ENTRIES));
	}

	@Example
	public void neverHoldsNullRanges() {
		assertThat(FetchEntries.builder().build().getRanges(), equalTo(List.of()));
	}

	@Example
	public void takesARangeUpToTheLastSequenceNumber(@Mocked ChannelHandlerContext context) throws Exception {
		final Range range = new Range(new WriterId(new byte[WriterId.SIZE]), 0, Long.MAX_VALUE);
		final FetchEntries packet = FetchEntries.builder().chainId(new ChainId(new byte[ChainId.SIZE]))
			.ranges(List.of(range)).build();

		assertThat(encodeDecode(packet, new FetchEntriesEncoder(), new FetchEntriesDecoder(), context),
			equalTo(packet));
	}

	@Example
	public void rangesHoldTheirBoundaries() {
		final WriterId writer = new WriterId(new byte[WriterId.SIZE]);

		assertThat(new Range(writer, 0, 0).toSeq(), equalTo(0L));
		assertThat(new Range(writer, Long.MAX_VALUE, Long.MAX_VALUE).fromSeq(), equalTo(Long.MAX_VALUE));
		assertThrows(IllegalArgumentException.class, () -> new Range(writer, -1, 0));
		assertThrows(IllegalArgumentException.class, () -> new Range(writer, 1, 0));
		assertThrows(NullPointerException.class, () -> new Range(null, 0, 0));
	}

	@Example
	public void refusesOneRangeMoreThanFitsInAPacket(@Mocked ChannelHandlerContext context) {
		final Range range = new Range(new WriterId(new byte[WriterId.SIZE]), 0, 0);
		final FetchEntries packet = FetchEntries.builder().chainId(new ChainId(new byte[ChainId.SIZE]))
			.ranges(Collections.nCopies(ChainCodecs.MAX_HEADS_PER_PACKET + 1, range)).build();
		final ByteBuf in = Unpooled.buffer().writeZero(ChainId.SIZE).writeShort(ChainCodecs.MAX_HEADS_PER_PACKET + 1);

		assertThrows(IllegalArgumentException.class, () -> new FetchEntriesEncoder().encode(context, packet));
		assertThrows(IllegalArgumentException.class, () -> new FetchEntriesDecoder().typedDecode(context, in));
	}
}
