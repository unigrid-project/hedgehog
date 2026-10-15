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
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PRECOMMIT;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PREVOTE;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class SignStateTest {
	private static final int LARGEST_PROOF = 4;
	private static final int SELF = 4;
	private static final int VIEW_OFFSET = 9;
	private static final int RUNNING_OFFSET = 8;
	private static final int STEP_OFFSET = 13;
	private static final int LOCKED_MARKER_OFFSET = 18;
	private static final int LOCKED_LENGTH_OFFSET = 19;
	private static final int PROPOSALS_COUNT_OFFSET = 28;
	private static final int BALLOTS_COUNT_OFFSET = 32;
	private static final byte FIRST_UNUSED_STEP = 3;
	private static final byte UNUSED_MARKER = 2;
	private static final String COUNT_BOUND = "holds at most";

	private static Block block(int n) {
		return CodecFixtures.block(new Random(n), 2, 3).toBuilder().height(1).build();
	}

	private static Proposal proposal(int view, int validRound, Block block, List<Ballot> polka) {
		return Proposal.signed(LedgerFixtures.seed(SELF), ConsensusFixtures.CHAIN, 1, view, validRound, block,
			polka);
	}

	private static Proposal proposal(int view, Block block) {
		return proposal(view, -1, block, List.of());
	}

	private static Ballot ballot(Ballot.Type type, int view, Block block) {
		return ConsensusFixtures.ballot(type, SELF, view, block);
	}

	private static Proposal reProposal() {
		final Block block = block(2);
		final List<Ballot> polka = IntStream.rangeClosed(1, 3)
			.mapToObj(validator -> ConsensusFixtures.ballot(PREVOTE, validator, 1, block)).toList();

		return proposal(3, 1, block, polka);
	}

	private static SignState of(int view, Block locked, int lockedView, Block valid, int validView,
		List<Proposal> proposals, List<Ballot> ballots) {
		return new SignState(1, true, view, Consensus.Step.PREVOTE, view + 1, locked, lockedView, valid, validView,
			proposals, ballots);
	}

	private static SignState counted(long height, int view, int nextView) {
		return new SignState(height, true, view, Consensus.Step.PROPOSE, nextView, null, -1, null, -1, List.of(),
			List.of());
	}

	private static SignState withBallots(Ballot... ballots) {
		return of(3, null, -1, null, -1, List.of(), List.of(ballots));
	}

	private static SignState withProposals(Proposal... proposals) {
		return of(3, null, -1, null, -1, List.of(proposals), List.of());
	}

	private static SignState full() {
		return of(3, block(1), 1, block(2), 2, List.of(proposal(0, block(1)), reProposal()),
			List.of(ballot(PREVOTE, 0, block(1)), ballot(PRECOMMIT, 0, null), ballot(PREVOTE, 1, block(2)),
				ballot(PRECOMMIT, 1, block(2)), ballot(PREVOTE, 3, null)));
	}

	private static void assertRoundTrip(SignState state) {
		final byte[] encoded = state.encode();
		final SignState decoded = SignState.decode(encoded, LARGEST_PROOF);

		assertThat(decoded, equalTo(state));
		assertThat(decoded.encode(), equalTo(encoded));
	}

	private static String refusalOf(byte[] encoded) {
		return assertThrows(IllegalArgumentException.class, () -> SignState.decode(encoded, LARGEST_PROOF))
			.getMessage();
	}

	private static void assertRefused(byte[] encoded) {
		refusalOf(encoded);
	}

	@Example
	public void shouldRoundTripAnEmptyState() {
		assertRoundTrip(SignState.empty(1));
		assertRoundTrip(SignState.empty(77));
	}

	@Example
	public void shouldStartEmptyWithNothingSignedAndNothingLocked() {
		final SignState empty = SignState.empty(6);

		assertThat(empty.height(), equalTo(6L));
		assertThat(empty.running(), equalTo(false));
		assertThat(empty.view(), equalTo(0));
		assertThat(empty.nextView(), equalTo(0));
		assertThat(empty.step(), equalTo(Consensus.Step.PROPOSE));
		assertThat(empty.lockedBlock() == null && empty.validBlock() == null, equalTo(true));
		assertThat(empty.lockedView(), equalTo(-1));
		assertThat(empty.validView(), equalTo(-1));
		assertThat(empty.proposals().isEmpty() && empty.ballots().isEmpty(), equalTo(true));
	}

	@Example
	public void shouldLayOutAnEmptyStateAtFixedPlaces() {
		assertThat(Bytes.hex(SignState.empty(1).encode()), equalTo("0000000000000001" + "00" + "00000000" + "00"
			+ "00000000" + "00" + "ffffffff" + "00" + "ffffffff" + "00000000" + "00000000"));
	}

	@Example
	public void shouldRoundTripALockAndAValidBlockOfDifferentViews() {
		assertRoundTrip(of(3, block(1), 1, block(2), 2, List.of(), List.of()));
		assertRoundTrip(of(3, block(1), 3, null, -1, List.of(), List.of()));
		assertRoundTrip(of(3, null, -1, block(2), 0, List.of(), List.of()));
	}

	@Example
	public void shouldRoundTripOwnProposalsAndKeepTheProofOfAReProposal() {
		final SignState state = withProposals(proposal(0, block(1)), reProposal());
		final SignState decoded = SignState.decode(state.encode(), LARGEST_PROOF);

		assertRoundTrip(state);
		assertThat(decoded.proposals().get(1).polka().size(), equalTo(3));
		assertThat(decoded.proposals().get(1).hasValidSignature(ConsensusFixtures.CHAIN), equalTo(true));
	}

	@Example
	public void shouldRoundTripBallotsOfBothTypesAndANilOne() {
		assertRoundTrip(withBallots(ballot(PREVOTE, 0, block(1)), ballot(PRECOMMIT, 0, block(1)),
			ballot(PREVOTE, 1, null), ballot(PRECOMMIT, 1, null)));
	}

	@Example
	public void shouldRoundTripAFullState() {
		assertRoundTrip(full());
	}

	@Example
	public void shouldRoundTripTheMostAnHonestNodeCanHold() {
		assertRoundTrip(of(1, null, -1, null, -1, List.of(proposal(0, block(1)), proposal(1, block(2))),
			List.of(ballot(PREVOTE, 0, null), ballot(PRECOMMIT, 0, null), ballot(PREVOTE, 1, null),
				ballot(PRECOMMIT, 1, null))));
	}

	@Example
	public void shouldRoundTripARunningStateAtEveryStep() {
		for (final Consensus.Step step : Consensus.Step.values()) {
			assertRoundTrip(new SignState(4, true, 2, step, 2, null, -1, null, -1, List.of(), List.of()));
			assertRoundTrip(new SignState(4, false, 2, step, 3, null, -1, null, -1, List.of(), List.of()));
		}
	}

	@Example
	public void shouldCopyTheListsItIsGiven() {
		final List<Ballot> ballots = new ArrayList<>(List.of(ballot(PREVOTE, 0, null)));
		final SignState state = of(3, null, -1, null, -1, List.of(), ballots);

		ballots.add(ballot(PRECOMMIT, 0, null));
		assertThat(state.ballots().size(), equalTo(1));
		assertThrows(UnsupportedOperationException.class, () -> state.ballots().add(ballot(PRECOMMIT, 0, null)));
		assertThrows(UnsupportedOperationException.class, () -> state.proposals().add(proposal(0, block(1))));
	}

	@Example
	public void shouldRefuseABallotOfAnotherHeight() {
		final Ballot other = Ballot.signed(PREVOTE, LedgerFixtures.seed(SELF), ConsensusFixtures.CHAIN, 2, 0, null);

		assertThrows(IllegalArgumentException.class, () -> withBallots(other));
	}

	@Example
	public void shouldRefuseAProposalOfAnotherHeight() {
		final Block second = block(1).toBuilder().height(2).build();
		final Proposal other = Proposal.signed(LedgerFixtures.seed(SELF), ConsensusFixtures.CHAIN, 2, 0, -1, second);

		assertThrows(IllegalArgumentException.class, () -> withProposals(other));
	}

	@Example
	public void shouldRefuseABlockOfAnotherHeight() {
		final Block second = block(1).toBuilder().height(2).build();

		assertThrows(IllegalArgumentException.class, () -> of(3, second, 1, null, -1, List.of(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> of(3, null, -1, second, 1, List.of(), List.of()));
	}

	@Example
	public void shouldRefuseTwoBallotsOfTheSameTypeAndView() {
		assertThrows(IllegalArgumentException.class,
			() -> withBallots(ballot(PREVOTE, 1, block(1)), ballot(PREVOTE, 1, block(2))));
		assertThrows(IllegalArgumentException.class,
			() -> withBallots(ballot(PRECOMMIT, 1, null), ballot(PRECOMMIT, 1, null)));
	}

	@Example
	public void shouldTakeBallotsOfOneViewIfTheirTypesDiffer() {
		assertRoundTrip(withBallots(ballot(PREVOTE, 1, null), ballot(PRECOMMIT, 1, null)));
	}

	@Example
	public void shouldRefuseTwoProposalsOfTheSameView() {
		assertThrows(IllegalArgumentException.class,
			() -> withProposals(proposal(1, block(1)), proposal(1, block(2))));
	}

	@Example
	public void shouldRefuseALockedBlockWithoutALockedViewAndTheReverse() {
		assertThrows(IllegalArgumentException.class, () -> of(3, block(1), -1, null, -1, List.of(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> of(3, null, 1, null, -1, List.of(), List.of()));
	}

	@Example
	public void shouldRefuseAValidBlockWithoutAValidViewAndTheReverse() {
		assertThrows(IllegalArgumentException.class, () -> of(3, null, -1, block(1), -1, List.of(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> of(3, null, -1, null, 1, List.of(), List.of()));
	}

	@Example
	public void shouldRefuseALockOrValidViewAboveTheView() {
		assertThrows(IllegalArgumentException.class, () -> of(3, block(1), 4, null, -1, List.of(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> of(3, null, -1, block(1), 4, List.of(), List.of()));
		assertRoundTrip(of(3, block(1), 3, block(2), 3, List.of(), List.of()));
	}

	@Example
	public void shouldRefuseANegativeViewOrNextViewOrHeightOrANullStep() {
		assertThrows(IllegalArgumentException.class, () -> counted(1, 0, -1));
		assertThrows(IllegalArgumentException.class, () -> counted(1, -1, 0));
		assertThrows(IllegalArgumentException.class, () -> counted(-1, 0, 0));
		assertThat(counted(0, 0, 0).height(), equalTo(0L));
		assertThrows(NullPointerException.class, () -> new SignState(1, true, 0, null, 0, null, -1, null, -1,
			List.of(), List.of()));
	}

	@Example
	public void shouldRefuseMoreEntriesThanAnHonestNodeCanSign() {
		final SignState most = of(0, null, -1, null, -1, List.of(proposal(0, block(1))),
			List.of(ballot(PREVOTE, 0, null), ballot(PRECOMMIT, 0, null)));

		assertThat(most.ballots().size(), equalTo(2));
		assertThrows(IllegalArgumentException.class, () -> of(0, null, -1, null, -1, List.of(proposal(0, block(1)),
			proposal(1, block(2))), List.of()));
		assertThrows(IllegalArgumentException.class, () -> of(0, null, -1, null, -1, List.of(),
			List.of(ballot(PREVOTE, 0, null), ballot(PRECOMMIT, 0, null), ballot(PREVOTE, 1, null))));
	}

	@Example
	public void shouldRefuseTrailingBytes() {
		final byte[] encoded = full().encode();

		assertRefused(Arrays.copyOf(encoded, encoded.length + 1));
	}

	@Example
	public void shouldRefuseAStepThatIsNoStep() {
		final byte[] encoded = full().encode();

		encoded[STEP_OFFSET] = FIRST_UNUSED_STEP;
		assertThat(refusalOf(encoded), containsString("step"));
		encoded[STEP_OFFSET] = -1;
		assertRefused(encoded);
	}

	@Example
	public void shouldRefuseAFlagThatIsNeitherYesNorNo() {
		final byte[] encoded = full().encode();

		encoded[RUNNING_OFFSET] = UNUSED_MARKER;
		assertRefused(encoded);
	}

	@Example
	public void shouldRefuseABlockMarkerThatIsNeitherPresentNorAbsent() {
		final byte[] encoded = full().encode();

		encoded[LOCKED_MARKER_OFFSET] = UNUSED_MARKER;
		assertRefused(encoded);
	}

	@Example
	public void shouldRefuseANegativeViewOnTheWire() {
		final byte[] encoded = SignState.empty(1).encode();

		ByteBuffer.wrap(encoded).putInt(VIEW_OFFSET, -1);
		assertRefused(encoded);
	}

	@Example
	public void shouldRefuseATruncatedStateAtEveryCutPoint() {
		final byte[] encoded = full().encode();

		IntStream.range(0, encoded.length).forEach(cut -> assertRefused(Arrays.copyOf(encoded, cut)));
	}

	@Example
	public void shouldRefuseMoreProposalsThanViewsBeforeReadingAny() {
		for (final int count : new int[] { 2, 1000, Integer.MAX_VALUE, -1 }) {
			final byte[] encoded = SignState.empty(1).encode();

			ByteBuffer.wrap(encoded).putInt(PROPOSALS_COUNT_OFFSET, count);
			assertThat(refusalOf(encoded), containsString(COUNT_BOUND));
		}
	}

	@Example
	public void shouldReadAsManyProposalsAsViewsUntilTheBytesRunOut() {
		final byte[] encoded = SignState.empty(1).encode();

		ByteBuffer.wrap(encoded).putInt(PROPOSALS_COUNT_OFFSET, 1);
		assertThat(refusalOf(encoded), not(containsString(COUNT_BOUND)));
	}

	@Example
	public void shouldRefuseMoreBallotsThanTwoPerViewBeforeReadingAny() {
		for (final int count : new int[] { 3, 1000, Integer.MAX_VALUE, -1 }) {
			final byte[] encoded = SignState.empty(1).encode();

			ByteBuffer.wrap(encoded).putInt(BALLOTS_COUNT_OFFSET, count);
			assertThat(refusalOf(encoded), containsString(COUNT_BOUND));
		}
	}

	@Example
	public void shouldReadAsManyBallotsAsTwoPerViewUntilTheBytesRunOut() {
		final byte[] encoded = SignState.empty(1).encode();

		ByteBuffer.wrap(encoded).putInt(BALLOTS_COUNT_OFFSET, 2);
		assertThat(refusalOf(encoded), not(containsString(COUNT_BOUND)));
	}

	@Example
	public void shouldAllowMoreEntriesAsTheViewGrows() {
		final byte[] encoded = SignState.empty(1).encode();

		ByteBuffer.wrap(encoded).putInt(VIEW_OFFSET, 100).putInt(PROPOSALS_COUNT_OFFSET, 101);
		assertThat(refusalOf(encoded), not(containsString(COUNT_BOUND)));
		ByteBuffer.wrap(encoded).putInt(PROPOSALS_COUNT_OFFSET, 102);
		assertThat(refusalOf(encoded), containsString(COUNT_BOUND));
	}

	@Example
	public void shouldRefuseAMessageOfTheWrongKindInTheListOfProposals() {
		final byte[] empty = SignState.empty(1).encode();
		final byte[] encoded = Bytes.concat(Arrays.copyOf(empty, PROPOSALS_COUNT_OFFSET), Bytes.intBytes(1),
			ConsensusCodec.encode(ballot(PREVOTE, 0, null)), Bytes.intBytes(0));

		assertThat(refusalOf(encoded), containsString("Proposal"));
	}

	@Example
	public void shouldRefuseAMessageOfTheWrongKindInTheListOfBallots() {
		final byte[] empty = SignState.empty(1).encode();
		final byte[] encoded = Bytes.concat(Arrays.copyOf(empty, BALLOTS_COUNT_OFFSET), Bytes.intBytes(1),
			ConsensusCodec.encode(proposal(0, block(1))));

		assertThat(refusalOf(encoded), containsString("Ballot"));
	}

	@Example
	public void shouldRefuseAProofLongerThanTheLargestProof() {
		final byte[] encoded = withProposals(reProposal()).encode();

		assertThat(SignState.decode(encoded, 3), equalTo(withProposals(reProposal())));
		assertThrows(IllegalArgumentException.class, () -> SignState.decode(encoded, 2));
	}

	@Example
	public void shouldRefuseABlockWhoseLengthIsNotAPossibleOne() {
		final byte[] valid = of(3, block(1), 1, null, -1, List.of(), List.of()).encode();
		final byte[] zero = valid.clone();
		final byte[] tooLong = valid.clone();

		ByteBuffer.wrap(zero).putInt(LOCKED_LENGTH_OFFSET, 0);
		ByteBuffer.wrap(tooLong).putInt(LOCKED_LENGTH_OFFSET, BlockCodec.MAX_ENCODED_SIZE + 1);

		assertRefused(zero);
		assertThat(refusalOf(tooLong), not(containsString("Truncated")));
	}
}
