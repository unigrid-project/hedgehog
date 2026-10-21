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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Negative;
import net.jqwik.api.constraints.Size;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.unigrid.hedgehog.ledger.Ballot;
import org.unigrid.hedgehog.ledger.Block;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Proposal;
import org.unigrid.hedgehog.ledger.ValidatorSet;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.network.Correlated;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockBatch;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockRequest;
import org.unigrid.hedgehog.model.network.packet.LedgerConsensus;
import org.unigrid.hedgehog.model.network.packet.LedgerHeartbeat;
import org.unigrid.hedgehog.model.network.packet.LedgerStatus;
import org.unigrid.hedgehog.model.network.packet.LedgerTransaction;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.service.ledger.LedgerWireFixtures;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.account;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.ballot;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.bodyOf;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.claim;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.encoded;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.largestBlock;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.random;

public class LedgerPacketIntegrityTest extends BaseCodecTest<Packet> {
	private static final int BATCH_SLACK = 64 * 1024;

	private static final Map<Class<?>, Packet.Type> TYPES = Map.of(
		LedgerTransaction.class, Packet.Type.LEDGER_TRANSACTION, LedgerConsensus.class, Packet.Type.LEDGER_CONSENSUS,
		LedgerHeartbeat.class, Packet.Type.LEDGER_HEARTBEAT, LedgerStatus.class, Packet.Type.LEDGER_STATUS,
		LedgerBlockRequest.class, Packet.Type.LEDGER_BLOCK_REQUEST,
		LedgerBlockBatch.class, Packet.Type.LEDGER_BLOCK_BATCH);

	private static final Map<Packet.Type, Supplier<ChannelHandler>> ENCODERS = Map.of(
		Packet.Type.LEDGER_TRANSACTION, LedgerTransactionEncoder::new,
		Packet.Type.LEDGER_CONSENSUS, LedgerConsensusEncoder::new,
		Packet.Type.LEDGER_HEARTBEAT, LedgerHeartbeatEncoder::new,
		Packet.Type.LEDGER_STATUS, LedgerStatusEncoder::new,
		Packet.Type.LEDGER_BLOCK_REQUEST, LedgerBlockRequestEncoder::new,
		Packet.Type.LEDGER_BLOCK_BATCH, LedgerBlockBatchEncoder::new);

	private static final Map<Packet.Type, Supplier<ChannelHandler>> DECODERS = Map.of(
		Packet.Type.LEDGER_TRANSACTION, LedgerTransactionDecoder::new,
		Packet.Type.LEDGER_CONSENSUS, LedgerConsensusDecoder::new,
		Packet.Type.LEDGER_HEARTBEAT, LedgerHeartbeatDecoder::new,
		Packet.Type.LEDGER_STATUS, LedgerStatusDecoder::new,
		Packet.Type.LEDGER_BLOCK_REQUEST, LedgerBlockRequestDecoder::new,
		Packet.Type.LEDGER_BLOCK_BATCH, () -> new LedgerBlockBatchDecoder((channel, id) -> true));

	private static final Map<Packet.Type, Integer> LENGTH_CAPS = Map.of(
		Packet.Type.LEDGER_TRANSACTION, Claim.MAX_ENCODED_SIZE,
		Packet.Type.LEDGER_CONSENSUS, WireCodec.MAX_MESSAGE_SIZE,
		Packet.Type.LEDGER_BLOCK_BATCH, BlockBatch.MAX_BYTES + BATCH_SLACK);

	@Provide
	public Arbitrary<Packet> packets() {
		return LedgerWireFixtures.packets();
	}

	@Provide
	public Arbitrary<Packet> lengthPrefixedPackets() {
		return packets().filter(packet -> LENGTH_CAPS.containsKey(packet.getType()));
	}

	@Provide
	public Arbitrary<Packet.Type> lengthPrefixedTypes() {
		return Arbitraries.of(LENGTH_CAPS.keySet());
	}

	@Provide
	public Arbitrary<Packet.Type> ledgerTypes() {
		return Arbitraries.of(TYPES.values());
	}

	private static PacketEncoder<?> encoder(final Packet.Type type) {
		return (PacketEncoder<?>) ENCODERS.get(type).get();
	}

	private static PacketDecoder<?> decoder(final Packet.Type type) {
		return (PacketDecoder<?>) DECODERS.get(type).get();
	}

	private static List<Object> decode(final Packet.Type type, final ByteBuf body,
		final ChannelHandlerContext context) throws Exception {

		final List<Object> out = new ArrayList<>();

		decoder(type).decode(context, body, out);
		return out;
	}

	private static ByteBuf lengthPrefixed(final Packet.Type type, final int length) {
		final ByteBuf body = Unpooled.buffer();

		return (type == Packet.Type.LEDGER_BLOCK_BATCH ? body.writeLong(1) : body).writeInt(length);
	}

	private static void assertOnlyRefuses(final Callable<?> decode) {
		try {
			decode.call();
		} catch (Exception thrown) {
			assertThat(thrown, anyOf(instanceOf(IllegalArgumentException.class),
				instanceOf(IndexOutOfBoundsException.class)));
		}
	}

	@SneakyThrows
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Packet roundTrip(final Packet packet, final ChannelHandlerContext context) {
		final Pair<MutableInt, MutableInt> sizes = getSizeHolder().get();
		final Packet decoded = encodeDecode(packet, (PacketEncoder) encoder(packet.getType()),
			(PacketDecoder) decoder(packet.getType()), context, Optional.of(sizes));

		assertThat("the decoder takes the whole body", sizes.getRight(), equalTo(sizes.getLeft()));
		return decoded;
	}

	@Property(tries = 60)
	public void everyPacketRoundTrips(@ForAll("packets") Packet packet, @Mocked ChannelHandlerContext context) {
		assertThat(roundTrip(packet, context), equalTo(packet));
	}

	@Property(tries = 50)
	public void buildersSetThePacketType(@ForAll("packets") Packet packet) {
		assertThat(packet.getType(), equalTo(TYPES.get(packet.getClass())));
	}

	@Example
	public void everyCodecAnnouncesTheTypeOfItsPacket() {
		TYPES.values().forEach(type -> {
			assertThat(encoder(type).getCodecType(), equalTo(type));
			assertThat(decoder(type).getCodecType(), equalTo(type));
		});
	}

	@Example
	public void ledgerTypesHaveTheirValues() {
		final Map<Packet.Type, Short> values = Map.of(Packet.Type.LEDGER_TRANSACTION, (short) 4000,
			Packet.Type.LEDGER_CONSENSUS, (short) 4010, Packet.Type.LEDGER_HEARTBEAT, (short) 4020,
			Packet.Type.LEDGER_STATUS, (short) 4030, Packet.Type.LEDGER_BLOCK_REQUEST, (short) 4040,
			Packet.Type.LEDGER_BLOCK_BATCH, (short) 4050);

		values.forEach((type, value) -> {
			assertThat(type.getValue(), equalTo(value));
			assertThat(Packet.Type.get(value), equalTo(type));
		});
	}

	@Example
	public void requestsAndBatchesAreCorrelated() {
		final Correlated request = LedgerBlockRequest.builder().requestId(7).build();
		final Correlated batch = LedgerBlockBatch.builder().requestId(9).build();

		assertThat(request.getRequestId(), equalTo(7L));
		assertThat(batch.getRequestId(), equalTo(9L));
	}

	@Property(tries = 30)
	public void aDecoderOwnsItsTypeAndForwardsTheOthers(@ForAll("packets") Packet packet) {
		final ByteBuf frame = encoded(packet);
		final byte[] body = bodyOf(frame);

		DECODERS.forEach((type, decoder) -> {
			final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), decoder.get());

			channel.writeInbound(frame.retainedDuplicate());

			final Object read = channel.readInbound();

			if (type == packet.getType()) {
				assertThat(read, is((Object) packet));
			} else {
				assertThat(ByteBufUtil.getBytes((ByteBuf) read), equalTo(body));
			}
		});
	}

	@Property(tries = 30)
	public void packetsCrossAPipelineOfEveryLedgerCodec(@ForAll("packets") Packet packet) {
		final EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder());

		DECODERS.values().forEach(decoder -> channel.pipeline().addLast(decoder.get()));
		channel.writeInbound(encoded(packet));

		final Object read = channel.readInbound();

		assertThat(read, is((Object) packet));
	}

	@Property(tries = 30)
	public void decodersRefuseEveryTruncation(@ForAll("packets") Packet packet,
		@Mocked ChannelHandlerContext context) {

		final byte[] body = bodyOf(encoded(packet));

		IntStream.range(0, body.length).forEach(cut -> assertThrows(IndexOutOfBoundsException.class,
			() -> decode(packet.getType(), Unpooled.wrappedBuffer(Arrays.copyOf(body, cut)), context)));
	}

	@Property(tries = 30)
	public void decodersRefuseNegativeLengths(@ForAll("lengthPrefixedTypes") Packet.Type type,
		@ForAll @Negative int length, @Mocked ChannelHandlerContext context) {

		assertThrows(IllegalArgumentException.class, () -> decode(type, lengthPrefixed(type, length), context));
	}

	@Property(tries = 30)
	public void decodersRefuseLengthsAboveTheirCap(@ForAll("lengthPrefixedTypes") Packet.Type type,
		@ForAll @IntRange(min = 1) int excess, @Mocked ChannelHandlerContext context) {

		final int length = (int) Math.min((long) LENGTH_CAPS.get(type) + excess, Integer.MAX_VALUE);

		assertThrows(IllegalArgumentException.class, () -> decode(type, lengthPrefixed(type, length), context));
	}

	@Property(tries = 30)
	public void decodersAcceptLengthsUpToTheirCapAndWaitForTheBytes(@ForAll("lengthPrefixedTypes") Packet.Type type,
		@Mocked ChannelHandlerContext context) {

		assertThrows(IndexOutOfBoundsException.class,
			() -> decode(type, lengthPrefixed(type, LENGTH_CAPS.get(type)), context));
	}

	@Property(tries = 30)
	public void decodersRefuseLengthsBeyondTheBuffer(@ForAll("lengthPrefixedTypes") Packet.Type type,
		@ForAll @Size(max = 64) byte[] data, @ForAll @IntRange(min = 1, max = 99) int excess,
		@Mocked ChannelHandlerContext context) {

		final ByteBuf body = lengthPrefixed(type, data.length + excess).writeBytes(data);

		assertThrows(IndexOutOfBoundsException.class, () -> decode(type, body, context));
	}

	@Property(tries = 30)
	public void batchDecoderRefusesForgedBlockCounts(@ForAll long requestId,
		@ForAll @IntRange(min = 1, max = 0xFFFF) int count, @Mocked ChannelHandlerContext context) {

		final ByteBuf body = Unpooled.buffer().writeLong(requestId).writeInt(Short.BYTES).writeShort(count);

		assertThrows(IllegalArgumentException.class, () -> decode(Packet.Type.LEDGER_BLOCK_BATCH, body, context));
	}

	@Property(tries = 30)
	public void decodersRefuseBytesAfterTheObjectInsideTheirLength(@ForAll("lengthPrefixedPackets") Packet packet,
		@Mocked ChannelHandlerContext context) {

		final ByteBuf body = Unpooled.wrappedBuffer(bodyOf(encoded(packet)));
		final int at = packet.getType() == Packet.Type.LEDGER_BLOCK_BATCH ? Long.BYTES : 0;
		final ByteBuf forged = Unpooled.buffer().writeBytes(body, 0, at).writeInt(body.getInt(at) + 1)
			.writeBytes(body, at + Integer.BYTES, body.readableBytes() - at - Integer.BYTES).writeByte(0);

		assertThrows(IllegalArgumentException.class, () -> decode(packet.getType(), forged, context));
	}

	@Property(tries = 300)
	public void randomBytesAreOnlyEverRefusedAsExpected(@ForAll("ledgerTypes") Packet.Type type,
		@ForAll @Size(max = 600) byte[] body, @Mocked ChannelHandlerContext context) {

		assertOnlyRefuses(() -> decode(type, Unpooled.wrappedBuffer(body), context));
	}

	@Property(tries = 300)
	public void damagedPacketsAreOnlyEverRefusedAsExpected(@ForAll("packets") Packet packet, @ForAll long seed,
		@Mocked ChannelHandlerContext context) {

		final Random random = new Random(seed);
		final byte[] body = bodyOf(encoded(packet));

		IntStream.rangeClosed(0, random.nextInt(4))
			.forEach(i -> body[random.nextInt(body.length)] = (byte) random.nextInt());
		assertOnlyRefuses(() -> decode(packet.getType(), Unpooled.wrappedBuffer(body), context));
	}

	@Example
	public void theLargestTransactionIsWhatItsCapAllows(@Mocked ChannelHandlerContext context) {
		final Claim largest = claim(new Random(1), false);
		final Packet packet = LedgerTransaction.builder().transaction(largest).build();

		assertThat(largest.encode().length, equalTo(Claim.MAX_ENCODED_SIZE));
		assertThat(roundTrip(packet, context), equalTo(packet));
	}

	@Example
	public void theLargestProposalFitsItsCap(@Mocked ChannelHandlerContext context) {
		final Random random = new Random(2);
		final Block block = largestBlock(random);
		final List<Ballot> polka = IntStream.range(0, ValidatorSet.MAX_SIZE).mapToObj(i -> ballot(random,
			Ballot.Type.PREVOTE, 1, 0, block.hash())).toList();
		final Proposal proposal = new Proposal(1, 1, 0, block, polka, account(random),
			random(random, Ed25519.SIGNATURE_SIZE));
		final Packet packet = LedgerConsensus.builder().message(proposal).build();

		assertThat(WireCodec.encodeMessage(proposal).length, lessThanOrEqualTo(WireCodec.MAX_MESSAGE_SIZE));
		assertThat(roundTrip(packet, context), equalTo(packet));
	}

	@Example
	public void theLargestBatchFitsItsCap(@Mocked ChannelHandlerContext context) {
		final Block block = largestBlock(new Random(3));
		final BlockBatch batch = new BlockBatch(IntStream.rangeClosed(1, BlockBatch.MAX_BLOCKS)
			.mapToObj(height -> block.toBuilder().height(height).build()).toList());
		final Packet packet = LedgerBlockBatch.builder().requestId(5).batch(batch).build();

		assertThat(WireCodec.encode(batch).length, lessThanOrEqualTo(BlockBatch.MAX_BYTES + BATCH_SLACK));
		assertThat(roundTrip(packet, context), equalTo(packet));
	}
}
