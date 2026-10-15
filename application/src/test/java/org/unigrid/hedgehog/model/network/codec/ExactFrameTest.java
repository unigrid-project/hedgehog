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
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockBatch;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockRequest;
import org.unigrid.hedgehog.model.network.packet.LedgerConsensus;
import org.unigrid.hedgehog.model.network.packet.LedgerHeartbeat;
import org.unigrid.hedgehog.model.network.packet.LedgerStatus;
import org.unigrid.hedgehog.model.network.packet.LedgerTransaction;
import org.unigrid.hedgehog.model.network.packet.Ping;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.bodyOf;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.emptyBatch;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.encoded;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.heartbeat;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.message;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.request;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.resized;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.status;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.transaction;

/* A frame is the body of one packet, no more and no less: a frame that is not taken whole cannot be told from the
   start of the next, so it is refused, and what follows it on the stream is not decoded as if nothing happened */
public class ExactFrameTest {
	private static EmbeddedChannel pipeline() {
		return new EmbeddedChannel(new FrameDecoder(), new PingDecoder(), new LedgerTransactionDecoder(),
			new LedgerConsensusDecoder(), new LedgerHeartbeatDecoder(), new LedgerStatusDecoder(),
			new LedgerBlockRequestDecoder(), new LedgerBlockBatchDecoder((channel, id) -> true));
	}

	private static ByteBuf pingFrame(final long nanoTime) {
		final EmbeddedChannel channel = new EmbeddedChannel(new PingEncoder());

		channel.writeOutbound(Ping.builder().nanoTime(nanoTime).build());
		return channel.readOutbound();
	}

	@Provide
	Arbitrary<Packet> packets() {
		return Arbitraries.integers().between(0, 100).flatMap(seed -> Arbitraries.of(
			LedgerTransaction.builder().transaction(transaction(seed)).build(),
			LedgerConsensus.builder().message(message(seed)).build(),
			LedgerHeartbeat.builder().heartbeat(heartbeat(seed)).build(),
			LedgerStatus.builder().status(status(seed)).build(),
			LedgerBlockRequest.builder().requestId(seed).request(request(seed)).build(),
			LedgerBlockBatch.builder().requestId(seed).batch(emptyBatch()).build()));
	}

	@Property(tries = 50)
	public void takesAFrameWhole(@ForAll("packets") Packet packet) {
		final EmbeddedChannel channel = pipeline();

		channel.writeInbound(encoded(packet));

		assertThat(channel.<Object>readInbound(), equalTo(packet));
		assertThat(channel.<Object>readInbound(), nullValue());
	}

	@Property(tries = 50)
	public void refusesBytesAfterThePacket(@ForAll("packets") Packet packet,
		@ForAll @IntRange(min = 1, max = 16) int extra) {

		final EmbeddedChannel channel = pipeline();
		final ByteBuf frame = resized(encoded(packet), extra);

		assertThrows(DecoderException.class, () -> channel.writeInbound(frame));
		assertThat(channel.<Object>readInbound(), nullValue());
	}

	@Property(tries = 50)
	public void refusesAFrameThatEndsBeforeThePacket(@ForAll("packets") Packet packet,
		@ForAll @IntRange(min = 1, max = 1_000) int cut) {

		final EmbeddedChannel channel = pipeline();
		final ByteBuf whole = encoded(packet);
		final ByteBuf frame = resized(whole, -(1 + cut % bodyOf(whole).length));

		assertThrows(DecoderException.class, () -> channel.writeInbound(frame));
		assertThat(channel.<Object>readInbound(), nullValue());
	}

	@Property(tries = 50)
	public void doesNotMixAShortFrameIntoTheNextOne(@ForAll("packets") Packet packet,
		@ForAll @IntRange(min = 1, max = 1_000) int cut) {

		final EmbeddedChannel channel = pipeline();
		final ByteBuf whole = encoded(packet);
		final ByteBuf shorter = resized(whole, -(1 + cut % bodyOf(whole).length));

		assertThrows(DecoderException.class, () -> channel.writeInbound(shorter));
		channel.writeInbound(encoded(packet));

		assertThat(channel.<Object>readInbound(), equalTo(packet));
	}

	@Property(tries = 30)
	public void decodesTwoFramesOfOneRead(@ForAll("packets") Packet first, @ForAll("packets") Packet second) {
		final EmbeddedChannel channel = pipeline();

		channel.writeInbound(encoded(first).writeBytes(encoded(second)));

		assertThat(channel.<Object>readInbound(), equalTo(first));
		assertThat(channel.<Object>readInbound(), equalTo(second));
		assertThat(channel.<Object>readInbound(), nullValue());
	}

	@Property(tries = 50)
	public void decodesAFrameThatArrivesInPieces(@ForAll("packets") Packet packet,
		@ForAll @IntRange(min = 1, max = 1_000) int split) {

		final EmbeddedChannel channel = pipeline();
		final ByteBuf frame = encoded(packet);
		final int first = 1 + split % (frame.readableBytes() - 1);

		channel.writeInbound(frame.readRetainedSlice(first));
		assertThat(channel.<Object>readInbound(), nullValue());

		channel.writeInbound(frame.readRetainedSlice(frame.readableBytes()));
		assertThat(channel.<Object>readInbound(), equalTo(packet));
	}

	/* A batch whose body no decoder could read: a length of a couple of gigabytes, and a few bytes after it */
	private static ByteBuf unreadableBatch(final long requestId) {
		return Unpooled.buffer().writeShort(FrameDecoder.MAGIC).writeShort(Packet.Type.LEDGER_BLOCK_BATCH.getValue())
			.writeInt(Long.BYTES + 6).writeLong(requestId).writeBytes(new byte[] { 127, -1, -1, -1, 1, 2 });
	}

	@Property(tries = 20)
	public void leavesABatchThatNobodyAwaitsWholeAndUnread(@ForAll long requestId,
		@ForAll @IntRange(min = 0, max = 100) int seed) {

		final List<Long> asked = new ArrayList<>();
		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new LedgerBlockBatchDecoder(
			(stream, id) -> asked.add(id) && false), new LedgerStatusDecoder());
		final Packet next = LedgerStatus.builder().status(status(seed)).build();

		channel.writeInbound(unreadableBatch(requestId));
		channel.writeInbound(encoded(next));

		assertThat(channel.<Object>readInbound(), equalTo(next));
		assertThat(asked, equalTo(List.of(requestId)));
	}

	@Property(tries = 20)
	public void readsABatchThatIsAwaitedAndRefusesWhatIsNotOne(@ForAll long requestId) {
		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(),
			new LedgerBlockBatchDecoder((stream, id) -> id == requestId), new LedgerStatusDecoder());

		assertThrows(DecoderException.class, () -> channel.writeInbound(unreadableBatch(requestId)));
	}

	@Example
	public void appliesTheSameRuleToAPacketOfAnEarlierRelease() {
		final EmbeddedChannel channel = pipeline();

		channel.writeInbound(pingFrame(42));
		assertThat(((Ping) channel.readInbound()).getNanoTime(), is(42L));

		assertThrows(DecoderException.class, () -> channel.writeInbound(resized(pingFrame(42), 1)));
		assertThrows(DecoderException.class, () -> channel.writeInbound(resized(pingFrame(42), -1)));
	}
}
