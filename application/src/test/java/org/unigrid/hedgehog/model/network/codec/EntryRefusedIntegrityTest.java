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
import java.util.List;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Negative;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class EntryRefusedIntegrityTest extends BaseCodecTest<EntryRefused> {
	@Provide
	public Arbitrary<ChainId> chainIds() {
		return ChainArbitraries.chainIds();
	}

	@Provide
	public Arbitrary<WriterId> writers() {
		return ChainArbitraries.writers();
	}

	@Provide
	public Arbitrary<Entry> entries() {
		return ChainArbitraries.entries();
	}

	@Property(tries = 30)
	public void entryRefused(@ForAll("chainIds") ChainId chainId, @ForAll("writers") WriterId writer,
		@ForAll @LongRange(min = 0) long seq, @ForAll Admission admission, @Mocked ChannelHandlerContext context)
		throws Exception {

		final EntryRefused packet = EntryRefused.builder().chainId(chainId).writer(writer).seq(seq)
			.admission(admission).build();

		assertThat(encodeDecode(packet, new EntryRefusedEncoder(), new EntryRefusedDecoder(), context),
			equalTo(packet));
	}

	@Property(tries = 30)
	public void refusalNamesTheEntry(@ForAll("entries") Entry entry, @ForAll Admission admission) {
		final EntryRefused refusal = EntryRefused.of(entry, admission);

		assertThat(refusal.getChainId(), equalTo(entry.header().chainId()));
		assertThat(refusal.getWriter(), equalTo(entry.header().writer()));
		assertThat(refusal.getSeq(), equalTo(entry.header().seq()));
		assertThat(refusal.getAdmission(), equalTo(admission));
	}

	@Property(tries = 30)
	public void decoderRefusesUnknownAdmissions(@ForAll @IntRange(max = 0xFF) int ordinal,
		@Mocked ChannelHandlerContext context) {

		Assume.that(ordinal >= Admission.values().length);

		final ByteBuf in = Unpooled.buffer().writeZero(ChainId.SIZE + WriterId.SIZE).writeLong(0).writeByte(ordinal);

		assertThrows(IllegalArgumentException.class, () -> new EntryRefusedDecoder().typedDecode(context, in));
	}

	@Property(tries = 30)
	public void decoderRefusesNegativeSequenceNumbers(@ForAll @Negative long seq,
		@Mocked ChannelHandlerContext context) {

		final ByteBuf in = Unpooled.buffer().writeZero(ChainId.SIZE + WriterId.SIZE).writeLong(seq)
			.writeByte(Admission.FULL.ordinal());

		assertThrows(IllegalArgumentException.class, () -> new EntryRefusedDecoder().typedDecode(context, in));
	}

	@Example
	public void carriesItsPacketType() {
		assertThat(EntryRefused.builder().build().getType(), equalTo(Packet.Type.ENTRY_REFUSED));
		assertThat(Packet.Type.ENTRY_REFUSED.getValue(), equalTo((short) 5050));
		assertThat(new EntryRefusedEncoder().getCodecType(), equalTo(Packet.Type.ENTRY_REFUSED));
		assertThat(new EntryRefusedDecoder().getCodecType(), equalTo(Packet.Type.ENTRY_REFUSED));
	}

	@Example
	public void decoderHoldsTheBoundaries(@Mocked ChannelHandlerContext context) throws Exception {
		final Admission last = Admission.values()[Admission.values().length - 1];
		final ByteBuf in = Unpooled.buffer().writeZero(ChainId.SIZE + WriterId.SIZE).writeLong(0)
			.writeByte(last.ordinal());

		assertThat(new EntryRefusedDecoder().typedDecode(context, in).get().getAdmission(), equalTo(last));

		for (int ordinal : new int[] { Admission.values().length, 0xFF }) {
			final ByteBuf unknown = Unpooled.buffer().writeZero(ChainId.SIZE + WriterId.SIZE).writeLong(0)
				.writeByte(ordinal);

			assertThrows(IllegalArgumentException.class, () -> new EntryRefusedDecoder().typedDecode(context, unknown));
		}
	}

	@Example
	public void builderRefusesANegativeSequenceNumber() {
		assertThrows(IllegalArgumentException.class, () -> EntryRefused.builder().seq(-1).build());
		assertThat(EntryRefused.builder().seq(0).build().getSeq(), equalTo(0L));
	}

	@Example
	public void keepsTheWireLayoutAndAdmissionBytesOfDeployedNodes() throws Exception {
		final List<Admission> inWireOrder = List.of(Admission.ACCEPTED, Admission.AHEAD, Admission.DUPLICATE,
			Admission.REPLAYED, Admission.BROKEN, Admission.WRONG_CHAIN, Admission.TOO_LARGE, Admission.FUTURE,
			Admission.EXPIRED, Admission.NOT_AUTHENTIC, Admission.FULL, Admission.MALFORMED);

		assertThat(Admission.values().length, equalTo(inWireOrder.size()));

		for (int wireByte = 0; wireByte < inWireOrder.size(); wireByte++) {
			final ByteBuf out = new EntryRefusedEncoder().encode(null, EntryRefused.builder()
				.chainId(new ChainId(new byte[ChainId.SIZE])).writer(new WriterId(new byte[WriterId.SIZE]))
				.seq(0).admission(inWireOrder.get(wireByte)).build()).orElseThrow();

			assertThat(out.readableBytes(), equalTo(73));
			assertThat((int) out.getByte(72), equalTo(wireByte));
		}
	}
}
