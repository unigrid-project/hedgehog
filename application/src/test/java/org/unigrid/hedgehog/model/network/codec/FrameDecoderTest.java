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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import java.util.Arrays;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.Packet;

public class FrameDecoderTest {
	private static final int HEADER_SIZE = 8;
	private static final short UNKNOWN_TYPE = 12345;

	/* The most that a frame of the type may hold, and the least, written out here and not taken from the decoder */
	private static final Map<Packet.Type, Integer> LEDGER_CAPS = Map.of(
		Packet.Type.LEDGER_TRANSACTION, Integer.BYTES + Claim.MAX_ENCODED_SIZE,
		Packet.Type.LEDGER_CONSENSUS, Integer.BYTES + WireCodec.MAX_MESSAGE_SIZE,
		Packet.Type.LEDGER_HEARTBEAT, AccountKey.SIZE + Long.BYTES + Digests.HASH_SIZE + Long.BYTES
			+ Ed25519.SIGNATURE_SIZE,
		Packet.Type.LEDGER_STATUS, 2 * Digests.HASH_SIZE + Long.BYTES,
		Packet.Type.LEDGER_BLOCK_REQUEST, Long.BYTES + Long.BYTES + Integer.BYTES,
		Packet.Type.LEDGER_BLOCK_BATCH, Long.BYTES + Integer.BYTES + BlockBatch.MAX_BYTES + 64 * 1024);

	private static final Map<Packet.Type, Integer> LEDGER_MINIMA = Map.of(
		Packet.Type.LEDGER_TRANSACTION, Integer.BYTES, Packet.Type.LEDGER_CONSENSUS, Integer.BYTES,
		Packet.Type.LEDGER_HEARTBEAT, LEDGER_CAPS.get(Packet.Type.LEDGER_HEARTBEAT),
		Packet.Type.LEDGER_STATUS, LEDGER_CAPS.get(Packet.Type.LEDGER_STATUS),
		Packet.Type.LEDGER_BLOCK_REQUEST, LEDGER_CAPS.get(Packet.Type.LEDGER_BLOCK_REQUEST),
		Packet.Type.LEDGER_BLOCK_BATCH, Long.BYTES + Integer.BYTES);

	private static ByteBuf frameOf(byte[] payload) {
		return Unpooled.buffer().writeShort(FrameDecoder.MAGIC).writeShort(Packet.Type.PING.getValue())
			.writeInt(payload.length).writeBytes(payload);
	}

	private static ByteBuf headerOf(final short type, final int size) {
		return Unpooled.buffer().writeShort(FrameDecoder.MAGIC).writeShort(type).writeInt(size);
	}

	private static ByteBuf headerOf(final Packet.Type type, final int size) {
		return headerOf(type.getValue(), size);
	}

	@Provide
	Arbitrary<Packet.Type> ledgerTypes() {
		return Arbitraries.of(LEDGER_CAPS.keySet());
	}

	@Provide
	Arbitrary<Packet.Type> otherTypes() {
		return Arbitraries.of(Arrays.stream(Packet.Type.values()).filter(type -> type != Packet.Type.UNDEFINED
			&& !LEDGER_CAPS.containsKey(type)).toList());
	}

	@Property(tries = 50)
	public void refusesAHeaderAboveTheCapOfALedgerType(@ForAll("ledgerTypes") Packet.Type type,
		@ForAll @IntRange(min = 1, max = 100_000) int excess) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		assertThrows(TooLongFrameException.class, () -> channel.writeInbound(headerOf(type,
			LEDGER_CAPS.get(type) + excess)));
	}

	@Property(tries = 50)
	public void refusesAHeaderOfAHugeLengthForALedgerType(@ForAll("ledgerTypes") Packet.Type type,
		@ForAll @IntRange(min = Network.MAX_DATA_SIZE - 10, max = Integer.MAX_VALUE) int size) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		assertThrows(DecoderException.class, () -> channel.writeInbound(headerOf(type, size)));
	}

	@Example
	public void waitsForTheBodyOfAFrameThatIsAtTheCapOfItsType() {
		LEDGER_CAPS.forEach((type, cap) -> {
			final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

			channel.writeInbound(headerOf(type, cap));
			assertThat(channel.readInbound(), nullValue());
			assertThat(channel.attr(Packet.KEY).get(), equalTo(type));
		});
	}

	@Property(tries = 50)
	public void refusesAHeaderBelowTheLeastOfALedgerType(@ForAll("ledgerTypes") Packet.Type type,
		@ForAll @IntRange(min = 0, max = 1_000) int shortage) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());
		final int size = LEDGER_MINIMA.get(type) - 1 - shortage % LEDGER_MINIMA.get(type);

		assertThrows(CorruptedFrameException.class, () -> channel.writeInbound(headerOf(type, size)));
	}

	@Property(tries = 20)
	public void refusesANegativeLength(@ForAll Packet.Type type, @ForAll @IntRange(min = Integer.MIN_VALUE, max = -1)
		int size) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		assertThrows(DecoderException.class, () -> channel.writeInbound(headerOf(type, size)));
	}

	@Property(tries = 20)
	public void refusesAFrameOfAnUnknownTypeThatHoldsAnything(@ForAll @IntRange(min = 1, max = 1_000_000) int size) {
		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		assertThrows(TooLongFrameException.class, () -> channel.writeInbound(headerOf(UNKNOWN_TYPE, size)));
	}

	@Example
	public void passesAnEmptyFrameOfAnUnknownTypeOn() {
		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		channel.writeInbound(headerOf(UNKNOWN_TYPE, 0));
		assertThat(((ByteBuf) channel.readInbound()).readableBytes(), equalTo(0));
		assertThat(channel.attr(Packet.KEY).get(), equalTo(Packet.Type.UNDEFINED));
	}

	@Property(tries = 50)
	public void keepsTheCapOfTheStreamForTheOtherTypes(@ForAll("otherTypes") Packet.Type type,
		@ForAll @IntRange(min = 1, max = Network.MAX_DATA_SIZE - HEADER_SIZE) int size) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		channel.writeInbound(headerOf(type, size));
		assertThat(channel.readInbound(), nullValue());
	}

	@Property(tries = 20)
	public void refusesAnythingAboveTheCapOfTheStreamForTheOtherTypes(@ForAll("otherTypes") Packet.Type type,
		@ForAll @IntRange(min = 1, max = 1_000_000) int excess) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		assertThrows(TooLongFrameException.class, () -> channel.writeInbound(headerOf(type,
			Network.MAX_DATA_SIZE - HEADER_SIZE + excess)));
	}

	/* A stream hands over whatever bytes have arrived, so a read may end anywhere inside the header */
	@Property
	public void shouldWaitForTheRestOfASplitFrame(@ForAll @IntRange(min = 1, max = HEADER_SIZE + 8) int split,
		@ForAll @Size(min = 1, max = 64) byte[] payload) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());
		final ByteBuf frame = frameOf(payload);
		final int firstPart = Math.min(split, frame.readableBytes() - 1);

		channel.writeInbound(frame.readRetainedSlice(firstPart));
		assertThat(channel.readInbound(), nullValue());

		channel.writeInbound(frame.readRetainedSlice(frame.readableBytes()));

		final ByteBuf decoded = channel.readInbound();

		assertThat(ByteBufUtil.getBytes(decoded), equalTo(payload));
		assertThat(channel.attr(Packet.KEY).get(), equalTo(Packet.Type.PING));
	}
}
