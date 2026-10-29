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
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Negative;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryCodec;
import org.unigrid.hedgehog.proxy.chain.EntryHeader;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.Tag;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.chain.WriterKey;

public class ChainCodecsTest {
	private static final WriterKey KEY = new WriterKey(new byte[WriterKey.SEED_SIZE]);
	private static final ChainId CHAIN = new ChainId(new byte[ChainId.SIZE]);

	private static Entry entry(final long seq, final int payloadBytes) {
		return KEY.sign(new EntryHeader(CHAIN, KEY.id(), seq, 0, 0, new Tag(new byte[Tag.SIZE])),
			new byte[payloadBytes]);
	}

	@Provide
	public Arbitrary<Entry> entries() {
		return ChainArbitraries.entries();
	}

	@Provide
	public Arbitrary<List<Entry>> entryLists() {
		return ChainArbitraries.entryLists();
	}

	@Provide
	public Arbitrary<Map<WriterId, Head>> heads() {
		return ChainArbitraries.heads();
	}

	@Property(tries = 50)
	public void entriesSurviveTheRoundTrip(@ForAll("entryLists") List<Entry> entries) {
		final ByteBuf buffer = Unpooled.buffer();

		ChainCodecs.writeEntries(buffer, entries);
		assertThat(ChainCodecs.readEntries(buffer), equalTo(entries));
		assertThat(buffer.isReadable(), is(false));
	}

	@Property(tries = 50)
	public void headsSurviveTheRoundTrip(@ForAll("heads") Map<WriterId, Head> heads) {
		final ByteBuf buffer = Unpooled.buffer();

		ChainCodecs.writeHeads(buffer, heads);
		assertThat(ChainCodecs.readHeads(buffer), equalTo(heads));
		assertThat(buffer.isReadable(), is(false));
	}

	/* The stamp and the end of each head go along, which the equality of heads does not compare */
	@Property(tries = 50)
	public void headsKeepTheirStampAndTheirEndThroughTheRoundTrip(@ForAll("heads") Map<WriterId, Head> heads) {
		final ByteBuf buffer = Unpooled.buffer();

		ChainCodecs.writeHeads(buffer, heads);

		final Map<WriterId, Head> read = ChainCodecs.readHeads(buffer);

		heads.forEach((writer, head) -> {
			assertThat(read.get(writer).timestamp(), is(head.timestamp()));
			assertThat(read.get(writer).last(), is(head.last()));
		});
	}

	@Property(tries = 30)
	public void refusesAMarkOfAHeadItDoesNotKnow(@ForAll @Size(WriterId.SIZE) byte[] writer, @ForAll byte mark) {
		Assume.that(mark != 0 && mark != 1);

		final ByteBuf in = Unpooled.buffer().writeShort(1).writeBytes(writer).writeLong(0).writeLong(0).writeLong(0)
			.writeByte(mark);

		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readHeads(in));
	}

	@Property(tries = 30)
	public void idsSurviveTheRoundTrip(@ForAll @Size(ChainId.SIZE) byte[] chain,
		@ForAll @Size(WriterId.SIZE) byte[] writer) {

		final ByteBuf buffer = Unpooled.buffer();

		ChainCodecs.writeChainId(buffer, new ChainId(chain));
		ChainCodecs.writeWriter(buffer, new WriterId(writer));
		assertThat(ChainCodecs.readChainId(buffer), equalTo(new ChainId(chain)));
		assertThat(ChainCodecs.readWriter(buffer), equalTo(new WriterId(writer)));
	}

	@Property(tries = 30)
	public void refusesEntryLengthsAboveTheLimitBeforeReadingThem(
		@ForAll @IntRange(min = ChainCodecs.MAX_ENTRY_BYTES + 1) int length) {

		final ByteBuf in = Unpooled.buffer().writeShort(1).writeInt(length);

		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readEntries(in));
	}

	@Property(tries = 30)
	public void refusesNegativeEntryLengths(@ForAll @Negative int length) {
		final ByteBuf in = Unpooled.buffer().writeShort(1).writeInt(length);

		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readEntries(in));
	}

	@Property(tries = 30)
	public void refusesEntryLengthsBeyondTheBuffer(@ForAll("entries") Entry entry,
		@ForAll @IntRange(min = 1, max = 64) int excess) {

		final byte[] encoded = EntryCodec.encode(entry);
		final ByteBuf in = Unpooled.buffer().writeShort(1).writeInt(encoded.length + excess).writeBytes(encoded);

		assertThrows(IndexOutOfBoundsException.class, () -> ChainCodecs.readEntries(in));
	}

	@Property(tries = 30)
	public void refusesEntriesWhoseLengthDisagreesWithTheirContent(@ForAll("entries") Entry entry,
		@ForAll @IntRange(min = -16, max = 16) int skew) {

		Assume.that(skew != 0);

		final byte[] encoded = EntryCodec.encode(entry);
		final ByteBuf in = Unpooled.buffer().writeShort(1).writeInt(encoded.length + skew).writeBytes(encoded)
			.writeZero(Math.max(skew, 0));

		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readEntries(in));
	}

	@Property(tries = 30)
	public void refusesMoreEntriesThanFitInAPacket(@ForAll("entries") Entry entry,
		@ForAll @IntRange(min = ChainCodecs.MAX_ENTRIES_PER_PACKET + 1, max = 0xFFFF) int count) {

		final ByteBuf in = Unpooled.buffer().writeShort(count);

		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readEntries(in));
		assertThrows(IllegalArgumentException.class,
			() -> ChainCodecs.writeEntries(Unpooled.buffer(), Collections.nCopies(count, entry)));
	}

	@Property(tries = 30)
	public void refusesMoreHeadsThanFitInAPacket(
		@ForAll @IntRange(min = ChainCodecs.MAX_HEADS_PER_PACKET + 1, max = 0xFFFF) int count) {

		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readHeads(Unpooled.buffer().writeShort(count)));
	}

	@Property(tries = 30)
	public void refusesTwoHeadsOfOneWriter(@ForAll @Size(WriterId.SIZE) byte[] writer,
		@ForAll @LongRange(min = 0) long seq, @ForAll long hash) {

		final ByteBuf in = Unpooled.buffer().writeShort(2);

		for (int i = 0; i < 2; i++) {
			in.writeBytes(writer).writeLong(seq).writeLong(hash).writeLong(0).writeByte(0);
		}

		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readHeads(in));
	}

	@Property(tries = 30)
	public void refusesNegativeHeadSequenceNumbers(@ForAll @Size(WriterId.SIZE) byte[] writer,
		@ForAll @Negative long seq, @ForAll long hash) {

		final ByteBuf in = Unpooled.buffer().writeShort(1).writeBytes(writer).writeLong(seq).writeLong(hash)
			.writeLong(0).writeByte(0);

		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readHeads(in));
	}

	@Example
	public void takesExactlyTheMostEntriesAndHeadsOfAPacket() {
		final List<Entry> entries = IntStream.range(0, ChainCodecs.MAX_ENTRIES_PER_PACKET)
			.mapToObj(seq -> entry(seq, 10)).collect(Collectors.toList());
		final Map<WriterId, Head> heads = IntStream.range(0, ChainCodecs.MAX_HEADS_PER_PACKET).boxed()
			.collect(Collectors.toMap(i -> new WriterId(ByteBuffer.allocate(WriterId.SIZE).putInt(i).array()),
				i -> new Head(i, i)));
		final ByteBuf buffer = Unpooled.buffer();

		ChainCodecs.writeEntries(buffer, entries);
		ChainCodecs.writeHeads(buffer, heads);
		assertThat(ChainCodecs.readEntries(buffer), equalTo(entries));
		assertThat(ChainCodecs.readHeads(buffer), equalTo(heads));
	}

	@Example
	public void takesAnEntryOfExactlyTheLargestSize() {
		final List<Entry> largest = List.of(entry(0, ChainCodecs.MAX_ENTRY_BYTES - Entry.OVERHEAD));
		final ByteBuf buffer = Unpooled.buffer();

		ChainCodecs.writeEntries(buffer, largest);
		assertThat(buffer.getInt(Short.BYTES), is(ChainCodecs.MAX_ENTRY_BYTES));
		assertThat(ChainCodecs.readEntries(buffer), equalTo(largest));
	}

	@Example
	public void refusesOneItemMoreThanFitsInAPacket() {
		final Entry entry = entry(0, 10);
		final Map<WriterId, Head> heads = IntStream.rangeClosed(0, ChainCodecs.MAX_HEADS_PER_PACKET).boxed()
			.collect(Collectors.toMap(i -> new WriterId(ByteBuffer.allocate(WriterId.SIZE).putInt(i).array()),
				i -> new Head(i, i)));

		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readEntries(Unpooled.buffer()
			.writeShort(ChainCodecs.MAX_ENTRIES_PER_PACKET + 1)));
		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.writeEntries(Unpooled.buffer(),
			Collections.nCopies(ChainCodecs.MAX_ENTRIES_PER_PACKET + 1, entry)));
		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readHeads(Unpooled.buffer()
			.writeShort(ChainCodecs.MAX_HEADS_PER_PACKET + 1)));
		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.writeHeads(Unpooled.buffer(), heads));
	}

	@Example
	public void readsCountsAsUnsigned() {
		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readEntries(Unpooled.buffer()
			.writeShort(0xFFFF)));
		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readHeads(Unpooled.buffer()
			.writeShort(0xFFFF)));
	}

	@Example
	public void refusesAnEntryLengthOneAboveTheLimitBeforeReadingIt() {
		final ByteBuf in = Unpooled.buffer().writeShort(1).writeInt(ChainCodecs.MAX_ENTRY_BYTES + 1);

		assertThrows(IllegalArgumentException.class, () -> ChainCodecs.readEntries(in));
	}

	@Example
	public void keepsTheWireLimits() {
		assertThat(ChainCodecs.MAX_ENTRY_BYTES, is(1 << 20));
		assertThat(ChainCodecs.MAX_ENTRIES_PER_PACKET, is(64));
		assertThat(ChainCodecs.MAX_HEADS_PER_PACKET, is(4096));
	}
}
