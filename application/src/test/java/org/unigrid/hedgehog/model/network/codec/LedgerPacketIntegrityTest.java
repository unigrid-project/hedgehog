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
import java.util.Collections;
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
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.ledger.Ballot;
import org.unigrid.hedgehog.ledger.Block;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.BlockRequest;
import org.unigrid.hedgehog.ledger.Certificate;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Decision;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Endorsement;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.LegacyAddress;
import org.unigrid.hedgehog.ledger.LegacySignature;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.Proposal;
import org.unigrid.hedgehog.ledger.Reference;
import org.unigrid.hedgehog.ledger.Status;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.ledger.ValidatorSet;
import org.unigrid.hedgehog.ledger.Vote;
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

public class LedgerPacketIntegrityTest extends BaseCodecTest<Packet> {
	private static final int FRAME_HEADER_SIZE = 8;
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

	private static byte[] random(final Random random, final int size) {
		final byte[] bytes = new byte[size];

		random.nextBytes(bytes);
		return bytes;
	}

	private static AccountKey account(final Random random) {
		return new AccountKey(random(random, AccountKey.SIZE));
	}

	private static Address address(final Random random) {
		return random.nextBoolean() ? account(random) : new LegacyAddress(random(random, LegacyAddress.SIZE));
	}

	private static Claim claim(final Random random, final boolean compressed) {
		final byte[] key = random(random, compressed ? 33 : 65);

		key[0] = (byte) (compressed ? 2 + random.nextInt(2) : 4);
		return new Claim(key, account(random), random(random, LegacySignature.SIZE));
	}

	private static Transaction transaction(final Random random) {
		return switch (random.nextInt(4)) {
			case 0 -> new Mint(address(random), 1 + random.nextInt(1_000_000),
				new Reference(random(random, Reference.SIZE)));
			case 1 -> new Vote(account(random), account(random), Vote.Action.values()[random.nextInt(2)],
				random.nextInt(1000), random(random, Ed25519.SIGNATURE_SIZE));
			case 2 -> claim(random, random.nextBoolean());
			default -> new Transfer(account(random), address(random), 1 + random.nextInt(1_000_000),
				random.nextInt(1000), random.nextInt(100), random(random, Ed25519.SIGNATURE_SIZE));
		};
	}

	private static Certificate certificate(final Random random, final int endorsements) {
		return new Certificate(random.nextInt(5), IntStream.range(0, endorsements)
			.mapToObj(i -> new Endorsement(account(random), random(random, Ed25519.SIGNATURE_SIZE))).toList());
	}

	private static Block block(final Random random, final long height, final List<Transaction> transactions,
		final int endorsements) {

		return Block.builder().height(height).view(random.nextInt(5)).previousHash(random(random, Digests.HASH_SIZE))
			.time(random.nextInt(100_000)).stateRoot(random(random, Digests.HASH_SIZE))
			.transactionRoot(random(random, Digests.HASH_SIZE)).transactions(transactions).proposer(account(random))
			.certificate(certificate(random, endorsements)).lastCommit(certificate(random, endorsements)).build();
	}

	private static Block block(final Random random, final long height) {
		return block(random, height, IntStream.range(0, random.nextInt(4)).mapToObj(i -> transaction(random)).toList(),
			random.nextInt(4));
	}

	private static Block largestBlock(final Random random) {
		return block(random, 1, Collections.nCopies(Block.MAX_TRANSACTIONS, claim(random, false)),
			ValidatorSet.MAX_SIZE);
	}

	private static Ballot ballot(final Random random, final Ballot.Type type, final long height, final int view,
		final byte[] blockHash) {

		return new Ballot(type, height, view, blockHash, account(random), random(random, Ed25519.SIGNATURE_SIZE));
	}

	private static Proposal proposal(final Random random, final Block block, final int proof) {
		final int validRound = proof == 0 ? random.nextInt(4) - 1 : random.nextInt(4);
		final List<Ballot> polka = IntStream.range(0, proof).mapToObj(i -> ballot(random, Ballot.Type.PREVOTE,
			block.getHeight(), validRound, block.hash())).toList();

		return new Proposal(block.getHeight(), validRound + 1 + random.nextInt(3), validRound, block, polka,
			account(random), random(random, Ed25519.SIGNATURE_SIZE));
	}

	private static Message message(final Random random) {
		final Block block = block(random, 1 + random.nextInt(1000));

		return switch (random.nextInt(4)) {
			case 0 -> ballot(random, Ballot.Type.values()[random.nextInt(2)], 1 + random.nextInt(1000),
				random.nextInt(5), random(random, Digests.HASH_SIZE));
			case 1 -> proposal(random, block, 0);
			case 2 -> proposal(random, block, 1 + random.nextInt(3));
			default -> new Decision(block);
		};
	}

	private static Heartbeat heartbeat(final Random random) {
		return new Heartbeat(account(random), random.nextLong(Long.MAX_VALUE), random(random, Digests.HASH_SIZE),
			random.nextLong(Long.MAX_VALUE), random(random, Ed25519.SIGNATURE_SIZE));
	}

	private static Status status(final Random random) {
		return new Status(random(random, Digests.HASH_SIZE), random.nextLong(Long.MAX_VALUE),
			random(random, Digests.HASH_SIZE));
	}

	private static BlockRequest request(final Random random) {
		return new BlockRequest(1 + random.nextLong(Long.MAX_VALUE - 1), 1 + random.nextInt(BlockBatch.MAX_BLOCKS));
	}

	private static BlockBatch batch(final Random random) {
		final long first = 1 + random.nextInt(1000);

		return new BlockBatch(IntStream.range(0, random.nextInt(4)).mapToObj(i -> block(random, first + i)).toList());
	}

	private static Packet packet(final Random random) {
		final long requestId = random.nextLong();

		return switch (random.nextInt(6)) {
			case 0 -> LedgerTransaction.builder().transaction(transaction(random)).build();
			case 1 -> LedgerConsensus.builder().message(message(random)).build();
			case 2 -> LedgerHeartbeat.builder().heartbeat(heartbeat(random)).build();
			case 3 -> LedgerStatus.builder().status(status(random)).build();
			case 4 -> LedgerBlockRequest.builder().requestId(requestId).request(request(random)).build();
			default -> LedgerBlockBatch.builder().requestId(requestId).batch(batch(random)).build();
		};
	}

	@Provide
	public Arbitrary<Packet> packets() {
		return Arbitraries.longs().map(seed -> packet(new Random(seed)));
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

	private static ByteBuf encoded(final Packet packet) {
		final EmbeddedChannel channel = new EmbeddedChannel(ENCODERS.values().stream().map(Supplier::get)
			.toArray(ChannelHandler[]::new));

		channel.writeOutbound(packet);
		return channel.readOutbound();
	}

	private static byte[] bodyOf(final Packet packet) {
		final ByteBuf frame = encoded(packet);

		return ByteBufUtil.getBytes(frame, FRAME_HEADER_SIZE, frame.readableBytes() - FRAME_HEADER_SIZE);
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
		final byte[] body = ByteBufUtil.getBytes(frame, FRAME_HEADER_SIZE, frame.readableBytes() - FRAME_HEADER_SIZE);

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

		final byte[] body = bodyOf(packet);

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

		final ByteBuf body = Unpooled.wrappedBuffer(bodyOf(packet));
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
		final byte[] body = bodyOf(packet);

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
