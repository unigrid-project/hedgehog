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
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PRECOMMIT;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PREVOTE;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class ConsensusCodecTest {
	private static final int LARGEST_PROOF = 4;
	private static final int PROOF_SIZE = 3;
	private static final int HEIGHT_OFFSET = 1;
	private static final int VIEW_OFFSET = HEIGHT_OFFSET + Long.BYTES;
	private static final int VALID_ROUND_OFFSET = VIEW_OFFSET + Integer.BYTES;
	private static final int BLOCK_LENGTH_OFFSET = VALID_ROUND_OFFSET + Integer.BYTES + AccountKey.SIZE
		+ Ed25519.SIGNATURE_SIZE;
	private static final int BALLOT_TYPE_OFFSET = 0;
	private static final int BALLOT_VIEW_OFFSET = BALLOT_TYPE_OFFSET + Byte.BYTES + Long.BYTES;
	private static final int BALLOT_HASH_OFFSET = BALLOT_VIEW_OFFSET + Integer.BYTES;

	private static Block block() {
		return CodecFixtures.block(new Random(11), 2, 3).toBuilder().height(1).build();
	}

	private static Proposal firstProposal() {
		return ConsensusFixtures.proposal(0, -1, block());
	}

	private static Proposal proposalWithProof(int ballots) {
		final Block block = block();
		final List<Ballot> polka = IntStream.rangeClosed(1, ballots)
			.mapToObj(validator -> ConsensusFixtures.ballot(PREVOTE, validator, 1, block)).toList();

		return ConsensusFixtures.proposal(3, 1, block, polka);
	}

	private static Decision decision() {
		return new Decision(CodecFixtures.block(new Random(5), 3, ValidatorSet.MAX_SIZE));
	}

	private static void assertRoundTrip(Message message) {
		final byte[] encoded = ConsensusCodec.encode(message);
		final Message decoded = ConsensusCodec.decode(encoded, LARGEST_PROOF);

		assertThat(decoded, equalTo(message));
		assertThat(ConsensusCodec.encode(decoded), equalTo(encoded));
	}

	private static void assertRefused(byte[] encoded) {
		assertThrows(IllegalArgumentException.class, () -> ConsensusCodec.decode(encoded, LARGEST_PROOF));
	}

	private static int proofBallotSize() {
		return ConsensusCodec.encode(ConsensusFixtures.ballot(PREVOTE, 1, 1, block())).length - Byte.BYTES;
	}

	private static int firstProofBallot(byte[] encoded) {
		return encoded.length - PROOF_SIZE * proofBallotSize();
	}

	private static byte[] withLength(byte kind, int length) {
		return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES).put(kind).putInt(length).array();
	}

	@Example
	public void shouldRoundTripAPrevote() {
		assertRoundTrip(ConsensusFixtures.ballot(PREVOTE, 2, 5, block()));
	}

	@Example
	public void shouldRoundTripAPrecommit() {
		assertRoundTrip(ConsensusFixtures.ballot(PRECOMMIT, 3, 7, block()));
	}

	@Example
	public void shouldRoundTripANilBallot() {
		assertRoundTrip(ConsensusFixtures.ballot(PREVOTE, 2, 4, null));
		assertRoundTrip(ConsensusFixtures.ballot(PRECOMMIT, 2, 4, null));
	}

	@Example
	public void shouldRoundTripAFirstProposal() {
		assertRoundTrip(firstProposal());
	}

	@Example
	public void shouldRoundTripAReProposalAndKeepItsProof() {
		final Proposal proposal = proposalWithProof(PROOF_SIZE);
		final Proposal decoded = (Proposal) ConsensusCodec.decode(ConsensusCodec.encode(proposal), LARGEST_PROOF);

		assertRoundTrip(proposal);
		assertThat(decoded.polka().size(), equalTo(PROOF_SIZE));
		assertThat(decoded.hasValidSignature(ConsensusFixtures.CHAIN), equalTo(true));
	}

	@Example
	public void shouldRoundTripADecisionWithAFullCertificate() {
		assertRoundTrip(decision());
	}

	@Property(tries = 50)
	public void shouldRoundTripAnyBallot(@ForAll boolean precommit, @ForAll @LongRange(min = 1, max = 1000) long height,
		@ForAll @IntRange(min = 0, max = 1000) int view, @ForAll boolean nil,
		@ForAll @IntRange(min = 1, max = 100) int validator) {
		final byte[] hash = Digests.sha512(Bytes.longBytes(height));

		assertRoundTrip(Ballot.signed(precommit ? PRECOMMIT : PREVOTE, LedgerFixtures.seed(validator),
			ConsensusFixtures.CHAIN, height, view, nil ? null : hash));
	}

	@Example
	public void shouldReadOneMessageFromABufferAndLeaveTheRest() {
		final Message message = proposalWithProof(PROOF_SIZE);
		final byte[] encoded = ConsensusCodec.encode(message);
		final ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + encoded.length + Long.BYTES);

		buffer.putInt(42);
		ConsensusCodec.encode(message, buffer);
		buffer.putLong(99L).flip();

		assertThat(buffer.getInt(), equalTo(42));
		assertThat(ConsensusCodec.decode(buffer, LARGEST_PROOF), equalTo(message));
		assertThat(buffer.remaining(), equalTo(Long.BYTES));
		assertThat(buffer.getLong(), equalTo(99L));
	}

	@Example
	public void shouldWriteWhatTheArrayFormHolds() {
		final Message message = ConsensusFixtures.ballot(PRECOMMIT, 2, 5, block());
		final ByteBuffer buffer = ByteBuffer.allocate(ConsensusCodec.encode(message).length);

		ConsensusCodec.encode(message, buffer);
		assertThat(buffer.array(), equalTo(ConsensusCodec.encode(message)));
	}

	@Example
	public void shouldRefuseEmptyInput() {
		assertRefused(new byte[0]);
	}

	@Example
	public void shouldRefuseAnUnknownKind() {
		assertRefused(new byte[] { 0 });
		assertRefused(new byte[] { 4 });
		assertRefused(new byte[] { (byte) 0xff });
	}

	@Example
	public void shouldRefuseATruncatedMessageAtEveryCutPoint() {
		final byte[] encoded = ConsensusCodec.encode(proposalWithProof(PROOF_SIZE));

		IntStream.range(0, encoded.length).forEach(cut -> assertRefused(Arrays.copyOf(encoded, cut)));
	}

	@Example
	public void shouldRefuseTrailingBytes() {
		final List<Message> messages = List.of(ConsensusFixtures.ballot(PREVOTE, 1, 1, block()), firstProposal(),
			proposalWithProof(PROOF_SIZE), decision());

		messages.forEach(message -> {
			final byte[] encoded = ConsensusCodec.encode(message);

			assertRefused(Arrays.copyOf(encoded, encoded.length + 1));
		});
	}

	@Example
	public void shouldRefuseABallotTypeThatIsNeitherPrevoteNorPrecommit() {
		final byte[] encoded = ConsensusCodec.encode(ConsensusFixtures.ballot(PREVOTE, 1, 1, block()));

		encoded[1] = 2;
		assertRefused(encoded);
	}

	@Example
	public void shouldRefuseAProposalWhoseValidRoundIsNotBelowItsView() {
		final byte[] encoded = ConsensusCodec.encode(firstProposal());

		ByteBuffer.wrap(encoded).putInt(VALID_ROUND_OFFSET, 0);
		assertRefused(encoded);
		ByteBuffer.wrap(encoded).putInt(VALID_ROUND_OFFSET, 1);
		assertRefused(encoded);
	}

	@Example
	public void shouldRefuseAProposalWhoseBlockIsOfAnotherHeight() {
		final byte[] encoded = ConsensusCodec.encode(firstProposal());

		ByteBuffer.wrap(encoded).putLong(HEIGHT_OFFSET, 2L);
		assertRefused(encoded);
	}

	@Example
	public void shouldRefuseAProofLongerThanTheLargestBeforeReadingAnyBallot() {
		final byte[] encoded = ConsensusCodec.encode(firstProposal());

		ByteBuffer.wrap(encoded).putShort(encoded.length - Short.BYTES, (short) 0xffff);

		final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> ConsensusCodec.decode(encoded, LARGEST_PROOF));

		assertThat(refused.getMessage(), containsString("65535"));
		assertThat(refused.getMessage(), not(containsString("Truncated")));
	}

	@Example
	public void shouldTakeAProofOfExactlyTheLargestSizeAndRefuseOneMore() {
		final byte[] encoded = ConsensusCodec.encode(proposalWithProof(PROOF_SIZE));

		assertThat(ConsensusCodec.decode(encoded, PROOF_SIZE), equalTo(proposalWithProof(PROOF_SIZE)));
		assertThrows(IllegalArgumentException.class, () -> ConsensusCodec.decode(encoded, PROOF_SIZE - 1));
	}

	@Example
	public void shouldRefuseABlockLengthOfZero() {
		assertRefused(withLength((byte) 3, 0));

		final byte[] encoded = ConsensusCodec.encode(firstProposal());

		ByteBuffer.wrap(encoded).putInt(BLOCK_LENGTH_OFFSET, 0);
		assertRefused(encoded);
	}

	@Example
	public void shouldRefuseABlockLengthAboveTheLargestBlockBeforeTakingItsBytes() {
		final int tooLong = BlockCodec.MAX_ENCODED_SIZE + 1;
		final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> ConsensusCodec.decode(withLength((byte) 3, tooLong), LARGEST_PROOF));

		assertThat(refused.getMessage(), containsString(String.valueOf(tooLong)));
		assertRefused(withLength((byte) 3, -1));

		final byte[] encoded = ConsensusCodec.encode(firstProposal());

		ByteBuffer.wrap(encoded).putInt(BLOCK_LENGTH_OFFSET, tooLong);
		assertRefused(encoded);
	}

	@Example
	public void shouldRefuseAProofBallotThatIsNotAPrevote() {
		final byte[] encoded = ConsensusCodec.encode(proposalWithProof(PROOF_SIZE));

		encoded[firstProofBallot(encoded) + BALLOT_TYPE_OFFSET] = 1;
		assertRefused(encoded);
	}

	@Example
	public void shouldRefuseAProofBallotOfAnotherView() {
		final byte[] encoded = ConsensusCodec.encode(proposalWithProof(PROOF_SIZE));

		ByteBuffer.wrap(encoded).putInt(firstProofBallot(encoded) + BALLOT_VIEW_OFFSET, 2);
		assertRefused(encoded);
	}

	@Example
	public void shouldRefuseAProofBallotForAnotherBlock() {
		final byte[] encoded = ConsensusCodec.encode(proposalWithProof(PROOF_SIZE));

		encoded[firstProofBallot(encoded) + BALLOT_HASH_OFFSET] ^= 1;
		assertRefused(encoded);
	}

	@Example
	public void shouldBoundTheLongestMessageByTheLargestProof() {
		for (int proof = 0; proof <= LARGEST_PROOF; proof++) {
			final int size = ConsensusCodec.encode(proposalWithProof(proof)).length;

			assertThat(ConsensusCodec.maxEncodedSize(proof), greaterThanOrEqualTo(size));
		}
	}

	@Example
	public void shouldGrowTheBoundByOneBallotPerUnitOfProof() {
		for (int proof = 0; proof < LARGEST_PROOF; proof++) {
			final int growth = ConsensusCodec.encode(proposalWithProof(proof + 1)).length
				- ConsensusCodec.encode(proposalWithProof(proof)).length;

			assertThat(ConsensusCodec.maxEncodedSize(proof + 1) - ConsensusCodec.maxEncodedSize(proof),
				equalTo(growth));
		}
	}

	@Example
	public void shouldBoundTheLongestMessageByTheLargestBlock() {
		final int largestDecision = ConsensusCodec.encode(decision()).length;

		assertThat(ConsensusCodec.maxEncodedSize(0), greaterThanOrEqualTo(BlockCodec.MAX_ENCODED_SIZE));
		assertThat(ConsensusCodec.maxEncodedSize(0), greaterThanOrEqualTo(largestDecision));
	}
}
