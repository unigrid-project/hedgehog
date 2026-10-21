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

package org.unigrid.hedgehog.ledger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PREVOTE;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class WireCodecTest {
	private static final int STATUS_SIZE = 64 + 8 + 64;
	private static final int REQUEST_SIZE = 8 + 4;
	private static final int HEARTBEAT_SIZE = 32 + 8 + 64 + 8 + 64;
	private static final int COUNT_SIZE = Short.BYTES;
	private static final List<Function<byte[], Object>> DECODERS = List.of(WireCodec::decodeStatus,
		WireCodec::decodeBlockRequest, WireCodec::decodeBlockBatch, WireCodec::decodeHeartbeat,
		WireCodec::decodeTransaction, WireCodec::decodeMessage);

	private static byte[] filled(int fill) {
		final byte[] bytes = new byte[Digests.HASH_SIZE];

		Arrays.fill(bytes, (byte) fill);
		return bytes;
	}

	private static Status status() {
		return new Status(filled(1), 42, filled(2));
	}

	private static BlockRequest request() {
		return new BlockRequest(7, 16);
	}

	private static Heartbeat heartbeat(Random random) {
		return new Heartbeat(CodecFixtures.account(random), random.nextLong(Long.MAX_VALUE),
			CodecFixtures.random(random, Digests.HASH_SIZE), random.nextLong(Long.MAX_VALUE),
			CodecFixtures.random(random, Ed25519.SIGNATURE_SIZE));
	}

	private static List<Block> consecutive(Random random, long first, int count) {
		return IntStream.range(0, count)
			.mapToObj(i -> CodecFixtures.block(random, 3, 3).toBuilder().height(first + i).build()).toList();
	}

	private static BlockBatch batch(int count) {
		return new BlockBatch(consecutive(new Random(count), 5, count));
	}

	private static Block block() {
		return CodecFixtures.block(new Random(11), 2, 3).toBuilder().height(1).build();
	}

	private static byte[] entry(byte[] encodedBlock) {
		return Bytes.concat(Bytes.intBytes(encodedBlock.length), encodedBlock);
	}

	private static byte[] batchOf(int count, byte[]... entries) {
		return Bytes.concat(ByteBuffer.allocate(COUNT_SIZE).putShort((short) count).array(), Bytes.concat(entries));
	}

	private static byte[] requestBytes(long from, int max) {
		return ByteBuffer.allocate(REQUEST_SIZE).putLong(from).putInt(max).array();
	}

	private static byte[] heartbeatBytes(long height, long time) {
		return ByteBuffer.allocate(HEARTBEAT_SIZE).put(filled(1), 0, AccountKey.SIZE).putLong(height).put(filled(2))
			.putLong(time).put(filled(3), 0, Ed25519.SIGNATURE_SIZE).array();
	}

	private static byte[] withTrailingByte(byte[] encoded) {
		return Arrays.copyOf(encoded, encoded.length + 1);
	}

	private static String refusalOf(Function<byte[], Object> decoder, byte[] encoded) {
		return assertThrows(IllegalArgumentException.class, () -> decoder.apply(encoded)).getMessage();
	}

	private static void assertRefusesEveryTruncation(byte[] encoded, Function<byte[], Object> decoder) {
		for (int cut = 0; cut < encoded.length; cut++) {
			final byte[] truncated = Arrays.copyOf(encoded, cut);

			assertThrows(IllegalArgumentException.class, () -> decoder.apply(truncated));
		}
	}

	private static void assertReturnsOrRefuses(Function<byte[], Object> decoder, byte[] data) {
		try {
			decoder.apply(data);
		} catch (Throwable thrown) {
			assertThat(thrown, instanceOf(IllegalArgumentException.class));
		}
	}

	private static Proposal proposalWithProof(int ballots) {
		final Block block = block();

		return ConsensusFixtures.proposal(3, 1, block,
			Collections.nCopies(ballots, ConsensusFixtures.ballot(PREVOTE, 1, 1, block)));
	}

	private static List<Message> messages() {
		final Block block = block();

		return List.of(ConsensusFixtures.ballot(PREVOTE, 2, 5, block),
			ConsensusFixtures.ballot(Ballot.Type.PRECOMMIT, 3, 7, null), proposalWithProof(0),
			proposalWithProof(3), new Decision(block));
	}

	private static List<byte[]> validEncodings(Random random) {
		final List<Message> messages = messages();

		return List.of(WireCodec.encode(status()), WireCodec.encode(request()), WireCodec.encode(batch(2)),
			WireCodec.encode(heartbeat(random)), CodecFixtures.transaction(random).encode(),
			WireCodec.encodeMessage(messages.get(random.nextInt(messages.size()))));
	}

	private static byte[] damaged(byte[] valid, Random random) {
		final byte[] data = valid.clone();
		final int changes = 1 + random.nextInt(3);

		for (int i = 0; i < changes; i++) {
			final int reach = random.nextBoolean() ? Math.min(data.length, 16) : data.length;

			data[random.nextInt(reach)] = (byte) random.nextInt(256);
		}

		return random.nextInt(4) == 0 ? Arrays.copyOf(data, random.nextInt(data.length + 1)) : data;
	}

	@Example
	public void shouldRoundTripAStatusWithExactBytes() {
		final byte[] encoded = WireCodec.encode(status());

		assertThat(encoded.length, equalTo(STATUS_SIZE));
		assertThat(WireCodec.decodeStatus(encoded), equalTo(status()));
		assertThat(WireCodec.encode(WireCodec.decodeStatus(encoded)), equalTo(encoded));
	}

	@Example
	public void shouldRoundTripAStatusAtGenesis() {
		final Status genesis = new Status(filled(1), 0, filled(0));

		assertThat(WireCodec.decodeStatus(WireCodec.encode(genesis)), equalTo(genesis));
	}

	@Example
	public void shouldRoundTripABlockRequestWithExactBytes() {
		final byte[] encoded = WireCodec.encode(request());

		assertThat(encoded.length, equalTo(REQUEST_SIZE));
		assertThat(WireCodec.decodeBlockRequest(encoded), equalTo(request()));
		assertThat(WireCodec.encode(WireCodec.decodeBlockRequest(encoded)), equalTo(encoded));
	}

	@Example
	public void shouldRoundTripABatchOfConsecutiveBlocksWithExactBytes() {
		final byte[] encoded = WireCodec.encode(batch(3));

		assertThat(WireCodec.decodeBlockBatch(encoded), equalTo(batch(3)));
		assertThat(WireCodec.encode(WireCodec.decodeBlockBatch(encoded)), equalTo(encoded));
	}

	@Example
	public void shouldRoundTripAnEmptyBatch() {
		final byte[] encoded = WireCodec.encode(new BlockBatch(List.of()));

		assertThat(encoded, equalTo(new byte[COUNT_SIZE]));
		assertThat(WireCodec.decodeBlockBatch(encoded).blocks().isEmpty(), is(true));
	}

	@Example
	public void shouldRoundTripAFullSizeHeartbeatWithExactBytes() {
		final Heartbeat full = new Heartbeat(Ed25519.publicKey(LedgerFixtures.seed(1)), Long.MAX_VALUE, filled(0xff),
			Long.MAX_VALUE, CodecFixtures.random(new Random(2), Ed25519.SIGNATURE_SIZE));
		final byte[] encoded = WireCodec.encode(full);

		assertThat(encoded.length, equalTo(HEARTBEAT_SIZE));
		assertThat(WireCodec.decodeHeartbeat(encoded), equalTo(full));
		assertThat(WireCodec.encode(WireCodec.decodeHeartbeat(encoded)), equalTo(encoded));
	}

	@Example
	public void shouldRoundTripATransactionOfEachKind() {
		final List<Transaction> transactions = List.of(CodecFixtures.transfer(new Random(1)),
			CodecFixtures.claim(new Random(2), true), CodecFixtures.claim(new Random(3), false),
			CodecFixtures.mint(new Random(4)), CodecFixtures.vote(new Random(5)));

		transactions.forEach(transaction -> {
			final byte[] encoded = transaction.encode();

			assertThat(WireCodec.decodeTransaction(encoded), equalTo(transaction));
			assertThat(WireCodec.decodeTransaction(encoded).encode(), equalTo(encoded));
		});
	}

	@Example
	public void shouldRoundTripAMessageOfEachKind() {
		messages().forEach(message -> {
			final byte[] encoded = WireCodec.encodeMessage(message);

			assertThat(WireCodec.decodeMessage(encoded), equalTo(message));
			assertThat(WireCodec.encodeMessage(WireCodec.decodeMessage(encoded)), equalTo(encoded));
		});
	}

	@Example
	public void shouldAcceptAProofOfTheLargestValidatorSet() {
		final Proposal proposal = proposalWithProof(ValidatorSet.MAX_SIZE);
		final byte[] encoded = WireCodec.encodeMessage(proposal);

		assertThat(WireCodec.decodeMessage(encoded), equalTo(proposal));
		assertThat(encoded.length, lessThanOrEqualTo(WireCodec.MAX_MESSAGE_SIZE));
	}

	@Example
	public void shouldRefuseAProofLargerThanTheLargestValidatorSet() {
		final byte[] encoded = WireCodec.encodeMessage(proposalWithProof(ValidatorSet.MAX_SIZE));
		final int proofSize = ValidatorSet.MAX_SIZE * (ConsensusCodec.BALLOT_SIZE - 1);

		ByteBuffer.wrap(encoded).putShort(encoded.length - proofSize - COUNT_SIZE,
			(short) (ValidatorSet.MAX_SIZE + 1));
		assertThat(refusalOf(WireCodec::decodeMessage, encoded), containsString("at most " + ValidatorSet.MAX_SIZE));
	}

	@Example
	public void shouldBoundAMessageByTheLargestProofAndBlock() {
		assertThat(WireCodec.MAX_MESSAGE_SIZE, equalTo(ConsensusCodec.maxEncodedSize(ValidatorSet.MAX_SIZE)));
		assertThat(WireCodec.MAX_MESSAGE_SIZE >= BlockCodec.MAX_ENCODED_SIZE, is(true));
	}

	@Example
	public void shouldRefuseEveryTruncationOfAStatus() {
		assertRefusesEveryTruncation(WireCodec.encode(status()), WireCodec::decodeStatus);
	}

	@Example
	public void shouldRefuseEveryTruncationOfABlockRequest() {
		assertRefusesEveryTruncation(WireCodec.encode(request()), WireCodec::decodeBlockRequest);
	}

	@Example
	public void shouldRefuseEveryTruncationOfABatchOfTwoBlocks() {
		assertRefusesEveryTruncation(WireCodec.encode(batch(2)), WireCodec::decodeBlockBatch);
	}

	@Example
	public void shouldRefuseEveryTruncationOfAHeartbeat() {
		assertRefusesEveryTruncation(WireCodec.encode(heartbeat(new Random(1))), WireCodec::decodeHeartbeat);
	}

	@Example
	public void shouldRefuseTrailingBytesAfterAStatus() {
		assertThrows(IllegalArgumentException.class,
			() -> WireCodec.decodeStatus(withTrailingByte(WireCodec.encode(status()))));
	}

	@Example
	public void shouldRefuseTrailingBytesAfterABlockRequest() {
		assertThrows(IllegalArgumentException.class,
			() -> WireCodec.decodeBlockRequest(withTrailingByte(WireCodec.encode(request()))));
	}

	@Example
	public void shouldRefuseTrailingBytesAfterABatch() {
		assertThrows(IllegalArgumentException.class,
			() -> WireCodec.decodeBlockBatch(withTrailingByte(WireCodec.encode(batch(2)))));
		assertThrows(IllegalArgumentException.class,
			() -> WireCodec.decodeBlockBatch(withTrailingByte(WireCodec.encode(batch(0)))));
	}

	@Example
	public void shouldRefuseTrailingBytesAfterAHeartbeat() {
		assertThrows(IllegalArgumentException.class,
			() -> WireCodec.decodeHeartbeat(withTrailingByte(WireCodec.encode(heartbeat(new Random(1))))));
	}

	@Example
	public void shouldRefuseTrailingBytesAfterATransaction() {
		assertThrows(IllegalArgumentException.class,
			() -> WireCodec.decodeTransaction(withTrailingByte(CodecFixtures.vote(new Random(1)).encode())));
	}

	@Example
	public void shouldRefuseTrailingBytesAfterAMessage() {
		assertThrows(IllegalArgumentException.class,
			() -> WireCodec.decodeMessage(withTrailingByte(WireCodec.encodeMessage(messages().get(0)))));
	}

	@Example
	public void shouldRefuseABlockRequestFromBelowOne() {
		assertThrows(IllegalArgumentException.class, () -> new BlockRequest(0, 1));
		assertThrows(IllegalArgumentException.class, () -> new BlockRequest(-1, 1));
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeBlockRequest(requestBytes(0, 1)));
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeBlockRequest(requestBytes(-1, 1)));
	}

	@Example
	public void shouldRefuseABlockRequestForNoBlocks() {
		assertThrows(IllegalArgumentException.class, () -> new BlockRequest(1, 0));
		assertThrows(IllegalArgumentException.class, () -> new BlockRequest(1, -1));
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeBlockRequest(requestBytes(1, 0)));
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeBlockRequest(requestBytes(1, -1)));
	}

	@Example
	public void shouldRefuseABlockRequestForMoreBlocksThanABatchHolds() {
		assertThat(BlockBatch.MAX_BLOCKS, equalTo(16));
		assertThrows(IllegalArgumentException.class, () -> new BlockRequest(1, 17));
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeBlockRequest(requestBytes(1, 17)));
		assertThat(WireCodec.decodeBlockRequest(requestBytes(1, 16)), equalTo(new BlockRequest(1, 16)));
	}

	@Example
	public void shouldRefuseABatchThatPromisesTheLargestCount() {
		final String refusal = refusalOf(WireCodec::decodeBlockBatch, batchOf(0xffff));

		assertThat(refusal, containsString("65535"));
		assertThat(refusal, not(containsString("Truncated")));
	}

	@Example
	public void shouldRefuseABatchOfOneBlockOverTheMaximum() {
		assertThat(refusalOf(WireCodec::decodeBlockBatch, batchOf(BlockBatch.MAX_BLOCKS + 1)),
			containsString("17"));
		assertThrows(IllegalArgumentException.class,
			() -> new BlockBatch(consecutive(new Random(1), 5, BlockBatch.MAX_BLOCKS + 1)));
	}

	@Example
	public void shouldAcceptABatchOfTheMaximumCount() {
		assertThat(WireCodec.decodeBlockBatch(WireCodec.encode(batch(BlockBatch.MAX_BLOCKS))).blocks().size(),
			equalTo(BlockBatch.MAX_BLOCKS));
	}

	@Example
	public void shouldRefuseABatchThatSkipsAHeight() {
		final List<Block> blocks = consecutive(new Random(1), 5, 3);
		final byte[] skipping = batchOf(2, entry(BlockCodec.encode(blocks.get(0))),
			entry(BlockCodec.encode(blocks.get(2))));

		assertThrows(IllegalArgumentException.class, () -> new BlockBatch(List.of(blocks.get(0), blocks.get(2))));
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeBlockBatch(skipping));
	}

	@Example
	public void shouldNotTakeTheLowestHeightForTheOneAfterTheHighest() {
		final Block top = block().toBuilder().height(Long.MAX_VALUE).build();
		final Block bottom = top.toBuilder().height(Long.MIN_VALUE).build();
		final byte[] wrapping = batchOf(2, entry(BlockCodec.encode(top)), entry(BlockCodec.encode(bottom)));

		assertThrows(IllegalArgumentException.class, () -> new BlockBatch(List.of(top, bottom)));
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeBlockBatch(wrapping));
		assertThat(new BlockBatch(List.of(top)).blocks().size(), equalTo(1));
	}

	@Example
	public void shouldRefuseABatchThatRepeatsOrReversesAHeight() {
		final List<Block> blocks = consecutive(new Random(1), 5, 2);
		final byte[] repeating = batchOf(2, entry(BlockCodec.encode(blocks.get(0))),
			entry(BlockCodec.encode(blocks.get(0))));
		final byte[] reversed = batchOf(2, entry(BlockCodec.encode(blocks.get(1))),
			entry(BlockCodec.encode(blocks.get(0))));

		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeBlockBatch(repeating));
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeBlockBatch(reversed));
	}

	@Example
	public void shouldCopyTheBlocksOfABatch() {
		final List<Block> blocks = new ArrayList<>(consecutive(new Random(1), 5, 2));
		final BlockBatch copied = new BlockBatch(blocks);

		blocks.clear();
		assertThat(copied.blocks().size(), equalTo(2));
		assertThrows(UnsupportedOperationException.class, () -> copied.blocks().clear());
	}

	@Example
	public void shouldRefuseABlockLengthOfZero() {
		final String refusal = refusalOf(WireCodec::decodeBlockBatch, batchOf(1, Bytes.intBytes(0)));

		assertThat(refusal, containsString("A block is 1 to " + BlockCodec.MAX_ENCODED_SIZE));
	}

	@Example
	public void shouldRefuseANegativeBlockLength() {
		assertThat(refusalOf(WireCodec::decodeBlockBatch, batchOf(1, Bytes.intBytes(-1))),
			containsString("A block is 1 to"));
	}

	@Example
	public void shouldRefuseABlockLengthOneAboveTheMaximumBeforeTakingTheBlock() {
		final String refusal = refusalOf(WireCodec::decodeBlockBatch,
			batchOf(1, Bytes.intBytes(BlockCodec.MAX_ENCODED_SIZE + 1)));

		assertThat(refusal, containsString("A block is 1 to " + BlockCodec.MAX_ENCODED_SIZE));
		assertThat(refusal, not(containsString("Truncated")));
	}

	@Example
	public void shouldLeaveRoomInTheByteBoundForTheLargestLegalBatch() {
		assertThat(BlockBatch.MAX_BLOCKS * BlockCodec.MAX_ENCODED_SIZE <= BlockBatch.MAX_BYTES, is(true));
		assertThat(BlockBatch.MAX_BYTES, equalTo(8 * 1024 * 1024));
	}

	@Example
	public void shouldRefuseABatchOverTheByteBoundBeforeTakingTheBlock() {
		final byte[] first = BlockCodec.encode(consecutive(new Random(1), 1, 1).get(0));
		final int bound = first.length + 10;
		final byte[] promised = batchOf(2, entry(first), Bytes.intBytes(11));
		final String refusal = assertThrows(IllegalArgumentException.class,
			() -> WireCodec.decodeBlockBatch(promised, bound)).getMessage();

		assertThat(refusal, containsString("at most " + bound + " bytes"));
		assertThat(refusal, not(containsString("Truncated")));
	}

	@Example
	public void shouldAcceptABatchThatFillsTheByteBoundExactly() {
		final List<Block> blocks = consecutive(new Random(1), 1, 2);
		final byte[] encoded = WireCodec.encode(new BlockBatch(blocks));
		final int total = encoded.length - COUNT_SIZE - 2 * Integer.BYTES;

		assertThat(WireCodec.decodeBlockBatch(encoded, total).blocks(), equalTo(blocks));
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeBlockBatch(encoded, total - 1));
	}

	@Example
	public void shouldRefuseAHeartbeatOfAHeightBelowZero() {
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeHeartbeat(heartbeatBytes(-1, 1)));
		assertThrows(IllegalArgumentException.class,
			() -> WireCodec.decodeHeartbeat(heartbeatBytes(Long.MIN_VALUE, 1)));
	}

	@Example
	public void shouldRefuseAHeartbeatOfATimeBelowZero() {
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeHeartbeat(heartbeatBytes(1, -1)));
		assertThrows(IllegalArgumentException.class,
			() -> WireCodec.decodeHeartbeat(heartbeatBytes(1, Long.MIN_VALUE)));
	}

	@Example
	public void shouldRefuseAStatusOfAHeightBelowZero() {
		assertThrows(IllegalArgumentException.class, () -> new Status(filled(1), -1, filled(2)));
		assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeStatus(
			Bytes.concat(filled(1), Bytes.longBytes(-1), filled(2))));
	}

	@Example
	public void shouldRefuseAStatusOfTheWrongSizes() {
		assertThrows(IllegalArgumentException.class, () -> new Status(new byte[63], 1, filled(2)));
		assertThrows(IllegalArgumentException.class, () -> new Status(filled(1), 1, new byte[65]));
	}

	@Example
	public void shouldKeepAStatusAsItWasMade() {
		final byte[] chainId = filled(1);
		final Status made = new Status(chainId, 3, filled(2));

		chainId[0] = 9;
		made.chainId()[1] = 9;
		made.tipHash()[2] = 9;

		assertThat(made, equalTo(new Status(filled(1), 3, filled(2))));
		assertThat(made.hashCode(), equalTo(new Status(filled(1), 3, filled(2)).hashCode()));
		assertThat(made, not(equalTo(new Status(filled(1), 4, filled(2)))));
		assertThat(made, not(equalTo(new Status(filled(3), 3, filled(2)))));
		assertThat(made, not(equalTo(new Status(filled(1), 3, filled(3)))));
	}

	@Example
	public void shouldWriteAStatusAsTheGoldenBytes() {
		final Status golden = new Status(filled(0x11), 0x0102, filled(0x22));
		final byte[] expected = HexFormat.of().parseHex("11".repeat(64) + "0000000000000102" + "22".repeat(64));

		assertThat(WireCodec.encode(golden), equalTo(expected));
		assertThat(WireCodec.decodeStatus(expected), equalTo(golden));
	}

	@Example
	public void shouldWriteABlockRequestAsTheGoldenBytes() {
		final byte[] expected = HexFormat.of().parseHex("0000000000000103" + "00000010");

		assertThat(WireCodec.encode(new BlockRequest(0x103, 16)), equalTo(expected));
		assertThat(WireCodec.decodeBlockRequest(expected), equalTo(new BlockRequest(0x103, 16)));
	}

	@Property(tries = 100)
	public void shouldReturnOrRefuseWithAnIllegalArgumentExceptionOnRandomBytes(
		@ForAll @Size(max = 600) byte[] data) {
		DECODERS.forEach(decoder -> assertReturnsOrRefuses(decoder, data));
	}

	@Property(tries = 300)
	public void shouldReturnOrRefuseWithAnIllegalArgumentExceptionOnDamagedEncodings(@ForAll long seed) {
		final Random random = new Random(seed);
		final List<byte[]> valid = validEncodings(random);

		IntStream.range(0, DECODERS.size())
			.forEach(i -> assertReturnsOrRefuses(DECODERS.get(i), damaged(valid.get(i), random)));
	}
}
