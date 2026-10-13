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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryCodec;
import org.unigrid.hedgehog.proxy.chain.EntryHeader;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterKey;

public class ChainEntryIntegrityTest extends BaseCodecTest<ChainEntry> {
	private static final int KIND_OFFSET = Entry.HEADER_SIZE - Integer.BYTES - Byte.BYTES;
	private static final int FIRST_ENTRY_OFFSET = Short.BYTES + Integer.BYTES;
	private static final int LARGEST_PAYLOAD = ChainCodecs.MAX_ENTRY_BYTES - Entry.OVERHEAD;
	private static final WriterKey KEY = new WriterKey(new byte[WriterKey.SEED_SIZE]);

	private static Entry entry(final long seq, final int payloadBytes) {
		return KEY.sign(new EntryHeader(new ChainId(new byte[ChainId.SIZE]), KEY.id(), seq, 0, 0, Fingerprint.NONE,
			EntryKind.SEALED), new byte[payloadBytes]);
	}

	private static long frameBytes(final ChainEntry batch) throws Exception {
		return ChainCodecs.FRAME_HEADER_BYTES + new ChainEntryEncoder().encode(null, batch).orElseThrow()
			.readableBytes();
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
	public Arbitrary<List<Entry>> manyEntries() {
		return ChainArbitraries.entries().list().ofMaxSize(4 * ChainCodecs.MAX_ENTRIES_PER_PACKET);
	}

	@Property(tries = 30)
	public void chainEntry(@ForAll("entryLists") List<Entry> entries, @Mocked ChannelHandlerContext context)
		throws Exception {

		final ChainEntry packet = ChainEntry.builder().entries(entries).build();

		assertThat(encodeDecode(packet, new ChainEntryEncoder(), new ChainEntryDecoder(), context), equalTo(packet));
	}

	@Property(tries = 10)
	public void encoderRefusesTooManyEntries(@ForAll("entries") Entry entry,
		@ForAll @IntRange(min = ChainCodecs.MAX_ENTRIES_PER_PACKET + 1, max = 0xFFFF) int count,
		@Mocked ChannelHandlerContext context) {

		final ChainEntry packet = ChainEntry.builder().entries(Collections.nCopies(count, entry)).build();

		assertThrows(IllegalArgumentException.class, () -> new ChainEntryEncoder().encode(context, packet));
	}

	@Property(tries = 30)
	public void decoderRefusesTooManyEntries(
		@ForAll @IntRange(min = ChainCodecs.MAX_ENTRIES_PER_PACKET + 1, max = 0xFFFF) int count,
		@Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeShort(count);

		assertThrows(IllegalArgumentException.class, () -> new ChainEntryDecoder().typedDecode(context, in));
	}

	@Property(tries = 30)
	public void decoderRefusesOversizedEntriesBeforeReadingThem(
		@ForAll @IntRange(min = ChainCodecs.MAX_ENTRY_BYTES + 1) int length, @Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeShort(1).writeInt(length);

		assertThrows(IllegalArgumentException.class, () -> new ChainEntryDecoder().typedDecode(context, in));
	}

	@Property(tries = 30)
	public void decoderRefusesEntriesOfAnUnknownKind(@ForAll("entries") Entry entry,
		@Mocked ChannelHandlerContext context) {

		final byte[] encoded = EntryCodec.encode(entry);
		final ByteBuf in = Unpooled.buffer().writeShort(1).writeInt(encoded.length).writeBytes(encoded);

		in.setByte(FIRST_ENTRY_OFFSET + KIND_OFFSET, 0);
		assertThrows(IllegalArgumentException.class, () -> new ChainEntryDecoder().typedDecode(context, in));
	}

	@Property(tries = 10)
	public void batchesKeepTheOrderWithinTheBound(@ForAll("manyEntries") List<Entry> entries) {
		final List<ChainEntry> batches = ChainEntry.batches(entries);
		final List<List<Entry>> contents = batches.stream().map(ChainEntry::getEntries).collect(Collectors.toList());

		assertThat(contents.stream().flatMap(List::stream).collect(Collectors.toList()), equalTo(entries));
		assertThat(contents.stream().map(List::size).collect(Collectors.toList()),
			everyItem(lessThanOrEqualTo(ChainCodecs.MAX_ENTRIES_PER_PACKET)));
		assertThat(batches.size(), equalTo((entries.size() + ChainCodecs.MAX_ENTRIES_PER_PACKET - 1)
			/ ChainCodecs.MAX_ENTRIES_PER_PACKET));
	}

	@Property(tries = 5)
	public void everyBatchFitsTheFrameLimit(@ForAll @Size(min = 16, max = 24) List<@IntRange(
		min = LARGEST_PAYLOAD / 2, max = LARGEST_PAYLOAD) Integer> payloadBytes) throws Exception {

		final List<Entry> entries = IntStream.range(0, payloadBytes.size())
			.mapToObj(seq -> entry(seq, payloadBytes.get(seq))).collect(Collectors.toList());
		final List<ChainEntry> batches = ChainEntry.batches(entries);

		for (ChainEntry batch : batches) {
			assertThat(frameBytes(batch), lessThanOrEqualTo((long) ChainCodecs.MAX_FRAME_BYTES));
		}

		assertThat(batches.stream().flatMap(batch -> batch.getEntries().stream()).collect(Collectors.toList()),
			equalTo(entries));
	}

	private static List<Entry> largest(final int count) {
		return IntStream.range(0, count).mapToObj(seq -> entry(seq, LARGEST_PAYLOAD)).collect(Collectors.toList());
	}

	/* The payload of the one entry that fills a frame of seven largest entries to the last byte */
	private static int roomAfterSevenLargest() {
		return ChainCodecs.MAX_FRAME_BYTES - ChainCodecs.ENTRIES_FRAME_OVERHEAD
			- largest(7).stream().mapToInt(ChainCodecs::encodedBytes).sum() - Integer.BYTES - Entry.OVERHEAD;
	}

	private static List<Entry> sevenLargestAnd(final int payloadBytes) {
		return Stream.concat(largest(7).stream(), Stream.of(entry(7, payloadBytes))).collect(Collectors.toList());
	}

	private static ByteBuf wire(final List<Entry> entries) {
		final ByteBuf in = Unpooled.buffer().writeShort(entries.size());

		entries.forEach(entry -> in.writeInt(EntryCodec.encode(entry).length).writeBytes(EntryCodec.encode(entry)));
		return in;
	}

	@Example
	public void encoderTakesAFrameFilledExactlyAndRefusesOneByteMore() throws Exception {
		final ChainEntry exact = ChainEntry.builder().entries(sevenLargestAnd(roomAfterSevenLargest())).build();
		final ChainEntry over = ChainEntry.builder().entries(sevenLargestAnd(roomAfterSevenLargest() + 1)).build();

		assertThat(frameBytes(exact), is((long) ChainCodecs.MAX_FRAME_BYTES));
		assertThrows(IllegalArgumentException.class, () -> new ChainEntryEncoder().encode(null, over));
	}

	@Example
	public void decoderTakesAFrameFilledExactlyAndRefusesOneByteMore() throws Exception {
		final List<Entry> exact = sevenLargestAnd(roomAfterSevenLargest());
		final List<Entry> over = sevenLargestAnd(roomAfterSevenLargest() + 1);

		assertThat(new ChainEntryDecoder().typedDecode(null, wire(exact)).orElseThrow().getEntries(), equalTo(exact));
		assertThrows(IllegalArgumentException.class, () -> new ChainEntryDecoder().typedDecode(null, wire(over)));
	}

	@Example
	public void fillsASecondFrameExactlyToTheLimitAndNoFurther() {
		final List<Entry> exact = Stream.concat(largest(7).stream(), sevenLargestAnd(roomAfterSevenLargest()).stream())
			.collect(Collectors.toList());
		final List<Entry> over = Stream.concat(largest(7).stream(), sevenLargestAnd(roomAfterSevenLargest() + 1).stream())
			.collect(Collectors.toList());

		assertThat(ChainEntry.batches(exact).stream().map(batch -> batch.getEntries().size())
			.collect(Collectors.toList()), equalTo(List.of(7, 8)));
		assertThat(ChainEntry.batches(over).stream().map(batch -> batch.getEntries().size())
			.collect(Collectors.toList()), equalTo(List.of(7, 7, 1)));
	}

	@Example
	public void fillsAFrameExactlyToTheLimitAndNoFurther() throws Exception {
		final List<Entry> largest = largest(7);
		final int room = roomAfterSevenLargest();
		final List<ChainEntry> exact = ChainEntry.batches(Stream.concat(largest.stream(),
			Stream.of(entry(7, room))).collect(Collectors.toList()));
		final List<ChainEntry> over = ChainEntry.batches(Stream.concat(largest.stream(),
			Stream.of(entry(7, room + 1))).collect(Collectors.toList()));

		assertThat(exact.size(), is(1));
		assertThat(frameBytes(exact.get(0)), is((long) ChainCodecs.MAX_FRAME_BYTES));
		assertThat(over.stream().map(batch -> batch.getEntries().size()).collect(Collectors.toList()),
			equalTo(List.of(7, 1)));
	}

	@Example
	public void carriesItsPacketType() {
		assertThat(ChainEntry.builder().build().getType(), equalTo(Packet.Type.CHAIN_ENTRY));
		assertThat(Packet.Type.CHAIN_ENTRY.getValue(), equalTo((short) 4020));
		assertThat(new ChainEntryEncoder().getCodecType(), equalTo(Packet.Type.CHAIN_ENTRY));
		assertThat(new ChainEntryDecoder().getCodecType(), equalTo(Packet.Type.CHAIN_ENTRY));
	}

	@Example
	public void batchesExactlyTheMostEntriesOfAPacketAndNoMore() {
		final List<Entry> entries = IntStream.rangeClosed(0, ChainCodecs.MAX_ENTRIES_PER_PACKET)
			.mapToObj(seq -> entry(seq, 10)).collect(Collectors.toList());
		final List<ChainEntry> full = ChainEntry.batches(entries.subList(0, ChainCodecs.MAX_ENTRIES_PER_PACKET));
		final List<ChainEntry> over = ChainEntry.batches(entries);

		assertThat(full.size(), is(1));
		assertThat(over.stream().map(batch -> batch.getEntries().size()).collect(Collectors.toList()),
			equalTo(List.of(ChainCodecs.MAX_ENTRIES_PER_PACKET, 1)));
		assertThat(ChainEntry.batches(List.of()), equalTo(List.of()));
	}

	@Example
	public void startsEveryFrameAfresh() {
		final List<Entry> largest = IntStream.range(0, 15).mapToObj(seq -> entry(seq, LARGEST_PAYLOAD))
			.collect(Collectors.toList());

		assertThat(ChainEntry.batches(largest).stream().map(batch -> batch.getEntries().size())
			.collect(Collectors.toList()), equalTo(List.of(7, 7, 1)));
	}

	@Example
	public void keepsTheFrameLimits() {
		assertThat(ChainCodecs.MAX_FRAME_BYTES, is(8 << 20));
		assertThat(ChainCodecs.FRAME_HEADER_BYTES, is(8));
		assertThat(ChainCodecs.ENTRIES_FRAME_OVERHEAD, is(10));
	}
}
