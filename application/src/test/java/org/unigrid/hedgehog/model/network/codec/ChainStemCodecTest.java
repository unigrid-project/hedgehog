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
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainStem;
import org.unigrid.hedgehog.proxy.chain.Entry;

public class ChainStemCodecTest extends BaseCodecTest<ChainStem> {
	@Provide
	public Arbitrary<Entry> entries() {
		return ChainArbitraries.entries();
	}

	@Provide
	public Arbitrary<List<Entry>> entryLists() {
		return ChainArbitraries.entryLists();
	}

	@Property(tries = 30)
	public void chainStem(@ForAll("entryLists") List<Entry> entries, @Mocked ChannelHandlerContext context)
		throws Exception {

		final ChainStem packet = ChainStem.builder().entries(entries).build();
		final ChainStem decoded = encodeDecode(packet, new ChainStemEncoder(), new ChainStemDecoder(), context);

		assertThat(decoded, equalTo(packet));
		assertThat(decoded.getType(), is(Packet.Type.CHAIN_STEM));
	}

	/* A stem is laid out as a flood is, so the bytes of one are those of the other */
	@Property(tries = 10)
	public void writesTheBytesOfAFlood(@ForAll("entryLists") List<Entry> entries,
		@Mocked ChannelHandlerContext context) throws Exception {

		final ByteBuf stem = new ChainStemEncoder().encode(context, new ChainStem(entries)).orElseThrow();
		final ByteBuf flood = new ChainEntryEncoder().encode(context, new ChainEntry(entries)).orElseThrow();

		assertThat(stem, equalTo(flood));
	}

	@Property(tries = 10)
	public void encoderRefusesTooManyEntries(@ForAll("entries") Entry entry,
		@ForAll @IntRange(min = ChainCodecs.MAX_ENTRIES_PER_PACKET + 1, max = 0xFFFF) int count,
		@Mocked ChannelHandlerContext context) {

		final ChainStem packet = ChainStem.builder().entries(Collections.nCopies(count, entry)).build();

		assertThrows(IllegalArgumentException.class, () -> new ChainStemEncoder().encode(context, packet));
	}

	@Property(tries = 10)
	public void decoderRefusesTooManyEntries(
		@ForAll @IntRange(min = ChainCodecs.MAX_ENTRIES_PER_PACKET + 1, max = 0xFFFF) int count,
		@Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeShort(count);

		assertThrows(IllegalArgumentException.class, () -> new ChainStemDecoder().typedDecode(context, in));
	}
}
