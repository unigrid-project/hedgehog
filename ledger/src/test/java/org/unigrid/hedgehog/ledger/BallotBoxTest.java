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

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class BallotBoxTest {
	private static final byte[] CHAIN = new byte[Digests.HASH_SIZE];
	private static final byte[] A = Digests.sha512("a".getBytes(StandardCharsets.US_ASCII));
	private static final byte[] B = Digests.sha512("b".getBytes(StandardCharsets.US_ASCII));
	private static final Ballot.Type PREVOTE = Ballot.Type.PREVOTE;
	private static final Ballot.Type PRECOMMIT = Ballot.Type.PRECOMMIT;

	private static BallotBox box() {
		return new BallotBox(1, new ValidatorSet(seeds(1, 4).stream().map(Ed25519::publicKey).toList()), CHAIN);
	}

	private static Ballot forged(Ballot.Type type, int validator, int view, byte[] hash) {
		return new Ballot(type, 1, view, hash, key(validator), ballot(type, validator + 1, view, hash).signature());
	}

	private static Ballot ballot(Ballot.Type type, int validator, int view, byte[] hash) {
		return Ballot.signed(type, seed(validator), CHAIN, 1, view, hash);
	}

	@Example
	public void shouldCountPerTypeViewAndHash() {
		final BallotBox box = box();

		box.add(ballot(PREVOTE, 1, 0, A));
		box.add(ballot(PREVOTE, 2, 0, A));
		box.add(ballot(PREVOTE, 3, 0, B));
		box.add(ballot(PREVOTE, 4, 1, A));
		box.add(ballot(PRECOMMIT, 1, 0, A));

		assertThat(box.count(PREVOTE, 0, A), equalTo(2));
		assertThat(box.count(PREVOTE, 0, B), equalTo(1));
		assertThat(box.countAny(PREVOTE, 0), equalTo(3));
		assertThat(box.countAny(PREVOTE, 1), equalTo(1));
		assertThat(box.count(PRECOMMIT, 0, A), equalTo(1));
	}

	@Example
	public void shouldHaveAQuorumAtMoreThanTwoThirds() {
		final BallotBox box = box();

		box.add(ballot(PREVOTE, 1, 0, A));
		box.add(ballot(PREVOTE, 2, 0, A));
		assertThat(box.quorumFor(PREVOTE, 0, A), is(false));

		box.add(ballot(PREVOTE, 3, 0, A));
		assertThat(box.quorumFor(PREVOTE, 0, A), is(true));
		assertThat(box.quorumAny(PREVOTE, 0), is(true));
		assertThat(box.quorumFor(PREVOTE, 0, B), is(false));
	}

	@Example
	public void shouldCountMixedBallotsOnlyForAnyQuorum() {
		final BallotBox box = box();

		box.add(ballot(PRECOMMIT, 1, 0, A));
		box.add(ballot(PRECOMMIT, 2, 0, B));
		box.add(ballot(PRECOMMIT, 3, 0, null));

		assertThat(box.quorumAny(PRECOMMIT, 0), is(true));
		assertThat(box.quorumFor(PRECOMMIT, 0, A), is(false));
		assertThat(box.quorumFor(PRECOMMIT, 0, Ballot.NIL), is(false));
	}

	@Example
	public void shouldHaveAQuorumForNilLikeForAnyBlock() {
		final BallotBox box = box();

		box.add(ballot(PRECOMMIT, 1, 0, null));
		box.add(ballot(PRECOMMIT, 2, 0, null));
		box.add(ballot(PRECOMMIT, 3, 0, null));

		assertThat(box.quorumFor(PRECOMMIT, 0, Ballot.NIL), is(true));
		assertThat(box.quorumFor(PRECOMMIT, 0, A), is(false));
	}

	@Example
	public void shouldKeepTheFirstBallotAndRecordAnEquivocation() {
		final BallotBox box = box();

		assertThat(box.add(ballot(PREVOTE, 1, 0, A)), equalTo(BallotBox.Result.ADDED));
		assertThat(box.add(ballot(PREVOTE, 1, 0, A)), equalTo(BallotBox.Result.DUPLICATE));
		assertThat(box.add(ballot(PREVOTE, 1, 0, B)), equalTo(BallotBox.Result.EQUIVOCATION));

		assertThat(box.count(PREVOTE, 0, A), equalTo(1));
		assertThat(box.count(PREVOTE, 0, B), equalTo(0));
		assertThat(box.evidence().size(), equalTo(1));
		assertThat(box.evidence().get(0).first(), equalTo(ballot(PREVOTE, 1, 0, A)));
		assertThat(box.evidence().get(0).second(), equalTo(ballot(PREVOTE, 1, 0, B)));
		assertThat(box.has(ballot(PREVOTE, 1, 0, A)), is(true));
		assertThat(box.has(ballot(PREVOTE, 1, 0, B)), is(false));
	}

	@Example
	public void shouldKeepOnePieceOfEvidenceForAValidatorThatKeepsSayingOtherThings() {
		final BallotBox box = box();
		final byte[] other = Digests.sha512("c".getBytes(StandardCharsets.US_ASCII));

		box.add(ballot(PREVOTE, 1, 0, A));

		assertThat(box.add(ballot(PREVOTE, 1, 0, B)), equalTo(BallotBox.Result.EQUIVOCATION));
		assertThat(box.add(ballot(PREVOTE, 1, 0, B)), equalTo(BallotBox.Result.DUPLICATE));
		assertThat(box.add(ballot(PREVOTE, 1, 0, other)), equalTo(BallotBox.Result.DUPLICATE));

		assertThat(box.evidence().size(), equalTo(1));
		assertThat(box.evidence().get(0).second(), equalTo(ballot(PREVOTE, 1, 0, B)));
		assertThat(box.add(ballot(PREVOTE, 1, 1, B)), equalTo(BallotBox.Result.ADDED));
		assertThat(box.add(ballot(PREVOTE, 1, 1, A)), equalTo(BallotBox.Result.EQUIVOCATION));
		assertThat(box.evidence().size(), equalTo(2));
	}

	@Example
	public void shouldRefuseAnotherHeightAndAKeyOutsideTheSet() {
		final BallotBox box = box();

		assertThat(box.add(Ballot.signed(PREVOTE, seed(1), CHAIN, 2, 0, A)), equalTo(BallotBox.Result.REFUSED));
		assertThat(box.add(ballot(PREVOTE, 9, 0, A)), equalTo(BallotBox.Result.REFUSED));
		assertThat(box.countAny(PREVOTE, 0), equalTo(0));
		assertThat(box.size(), equalTo(0));
	}

	@Example
	public void shouldRefuseABallotThatIsNotSignedByItsValidatorAndCountItNowhere() {
		final BallotBox box = box();

		assertThat(box.add(forged(PREVOTE, 1, 7, A)), equalTo(BallotBox.Result.REFUSED));
		assertThat(box.add(forged(PREVOTE, 2, 7, A)), equalTo(BallotBox.Result.REFUSED));

		assertThat(box.countAny(PREVOTE, 7), equalTo(0));
		assertThat(box.size(), equalTo(0));
		assertThat(box.ballotsBy(key(1)).size(), equalTo(0));
		assertThat(box.skipTo(0).isPresent(), is(false));
	}

	@Example
	public void shouldRefuseABallotSignedForAnotherChain() {
		final BallotBox box = box();
		final byte[] otherChain = new byte[Digests.HASH_SIZE];

		otherChain[0] = 1;

		assertThat(box.add(Ballot.signed(PRECOMMIT, seed(1), otherChain, 1, 0, A)),
			equalTo(BallotBox.Result.REFUSED));
		assertThat(box.size(), equalTo(0));
	}

	@Example
	public void shouldNotLetAForgedSecondBallotBecomeEvidence() {
		final BallotBox box = box();

		box.add(ballot(PREVOTE, 1, 0, A));

		assertThat(box.add(forged(PREVOTE, 1, 0, B)), equalTo(BallotBox.Result.REFUSED));
		assertThat(box.evidence().size(), equalTo(0));
		assertThat(box.add(ballot(PREVOTE, 1, 0, B)), equalTo(BallotBox.Result.EQUIVOCATION));
		assertThat(box.evidence().size(), equalTo(1));
	}

	@Example
	public void shouldNotVerifyABallotItAlreadyHolds() {
		final BallotBox box = box();
		final Ballot held = ballot(PREVOTE, 1, 0, A);
		final Ballot replayed = ConsensusFixtures.forged(held);

		box.add(held);

		assertThat(box.add(replayed), equalTo(BallotBox.Result.DUPLICATE));
	}

	@Example
	public void shouldHandOutTheEndorsementsOfAHashInKeyOrder() {
		final BallotBox box = box();
		final List<Integer> reverseKeyOrder = IntStream.rangeClosed(1, 4).boxed()
			.sorted(Comparator.comparing((Integer validator) -> key(validator)).reversed()).toList();

		reverseKeyOrder.forEach(validator -> box.add(ballot(PRECOMMIT, validator, 2, validator == 2 ? B : A)));

		final List<AccountKey> signers = box.endorsements(2, A).stream().map(Endorsement::signer).toList();

		assertThat(signers, equalTo(Stream.of(1, 3, 4).map(validator -> key(validator)).sorted().toList()));
	}

	@Example
	public void shouldSkipToTheViewMoreThanOneThirdHaveReached() {
		final BallotBox box = box();

		box.seen(key(1), 5);
		assertThat(box.skipTo(0).isPresent(), is(false));

		box.seen(key(2), 3);
		assertThat(box.skipTo(0).getAsInt(), equalTo(3));

		box.seen(key(3), 9);
		assertThat(box.skipTo(0).getAsInt(), equalTo(5));
		assertThat(box.skipTo(5).isPresent(), is(false));
		assertThat(box.skipTo(4).getAsInt(), equalTo(5));
	}

	@Example
	public void shouldCountAValidatorOnceAtItsHighestViewForASkip() {
		final BallotBox box = box();

		box.seen(key(1), 5);
		box.seen(key(1), 9);
		assertThat(box.skipTo(0).isPresent(), is(false));

		box.seen(key(1), 2);
		box.seen(key(2), 3);
		assertThat(box.skipTo(0).getAsInt(), equalTo(3));
	}

	@Example
	public void shouldNotCountAKeyOutsideTheSetForASkip() {
		final BallotBox box = box();

		box.seen(key(9), 7);
		box.seen(key(1), 7);

		assertThat(box.skipTo(0).isPresent(), is(false));
	}

	@Example
	public void shouldIgnoreANegativeView() {
		final BallotBox box = box();

		box.seen(key(1), -3);
		box.seen(key(2), -3);

		assertThat(box.skipTo(-5).isPresent(), is(false));
	}

	@Example
	public void shouldListTheBallotsOfOneValidator() {
		final BallotBox box = box();

		box.add(ballot(PREVOTE, 1, 0, A));
		box.add(ballot(PRECOMMIT, 1, 0, A));
		box.add(ballot(PREVOTE, 2, 0, A));

		assertThat(box.ballotsBy(key(1)).size(), equalTo(2));
		assertThat(box.ballotsBy(key(4)).size(), equalTo(0));
	}

	@Example
	public void shouldListTheBallotsOfOneValidatorInTheOrderTheyWereAdded() {
		final BallotBox box = box();
		final Ballot first = ballot(PREVOTE, 1, 1, A);
		final Ballot second = ballot(PRECOMMIT, 1, 0, A);
		final Ballot third = ballot(PREVOTE, 1, 0, B);

		box.add(ballot(PRECOMMIT, 2, 0, A));
		box.add(ballot(PREVOTE, 2, 1, A));
		box.add(first);
		box.add(second);
		box.add(third);

		assertThat(box.ballotsBy(key(1)), equalTo(List.of(first, second, third)));
	}

	@Example
	public void shouldNotListABallotThatWasNotStored() {
		final BallotBox box = box();
		final Ballot kept = ballot(PREVOTE, 1, 0, A);

		box.add(kept);
		box.add(ballot(PREVOTE, 1, 0, B));

		assertThat(box.ballotsBy(key(1)), equalTo(List.of(kept)));
	}

	@Example
	public void shouldListThePrevotesForOneHashAndViewInTheOrderTheyWereStored() {
		final BallotBox box = box();
		final Ballot first = ballot(PREVOTE, 3, 0, A);
		final Ballot second = ballot(PREVOTE, 1, 0, A);
		final Ballot third = ballot(PREVOTE, 4, 0, A);

		box.add(first);
		box.add(ballot(PREVOTE, 2, 0, B));
		box.add(second);
		box.add(ballot(PREVOTE, 2, 1, A));
		box.add(ballot(PRECOMMIT, 2, 0, A));
		box.add(third);

		assertThat(box.ballotsFor(PREVOTE, 0, A), equalTo(List.of(first, second, third)));
	}

	@Example
	public void shouldListNoBallotsForAnotherHashViewOrType() {
		final BallotBox box = box();

		box.add(ballot(PREVOTE, 1, 0, A));
		box.add(ballot(PRECOMMIT, 2, 0, A));

		assertThat(box.ballotsFor(PREVOTE, 0, B).isEmpty(), is(true));
		assertThat(box.ballotsFor(PREVOTE, 1, A).isEmpty(), is(true));
		assertThat(box.ballotsFor(PRECOMMIT, 0, B).isEmpty(), is(true));
		assertThat(box.ballotsFor(PREVOTE, 1_000_000, A).isEmpty(), is(true));
		assertThat(box.size(), equalTo(2));
	}

	@Example
	public void shouldNotListARefusedOrContradictingBallotForAHash() {
		final BallotBox box = box();
		final Ballot kept = ballot(PREVOTE, 1, 0, A);

		box.add(kept);
		box.add(ballot(PREVOTE, 1, 0, B));
		box.add(forged(PREVOTE, 2, 0, A));
		box.add(Ballot.signed(PREVOTE, seed(3), CHAIN, 2, 0, A));

		assertThat(box.ballotsFor(PREVOTE, 0, A), equalTo(List.of(kept)));
		assertThat(box.ballotsFor(PREVOTE, 0, B).isEmpty(), is(true));
	}

	@Example
	public void shouldCountTheBallotsStoredInAllSlots() {
		final BallotBox box = box();

		assertThat(box.size(), equalTo(0));

		box.add(ballot(PREVOTE, 1, 0, A));
		box.add(ballot(PREVOTE, 1, 0, A));
		box.add(ballot(PREVOTE, 1, 0, B));
		box.add(ballot(PRECOMMIT, 1, 0, A));
		box.add(ballot(PREVOTE, 2, 3, A));

		assertThat(box.size(), equalTo(3));
	}

	@Example
	public void shouldSayWhetherAViewSeenRaisedTheHighestViewOfThatValidator() {
		final BallotBox box = box();

		assertThat(box.seen(key(1), 5), is(true));
		assertThat(box.seen(key(1), 5), is(false));
		assertThat(box.seen(key(1), 3), is(false));
		assertThat(box.seen(key(1), 6), is(true));
		assertThat(box.seen(key(2), 5), is(true));
		assertThat(box.seen(key(9), 7), is(false));
		assertThat(box.seen(key(3), -1), is(false));
		assertThat(box.skipTo(0).getAsInt(), equalTo(5));
	}

	@Example
	public void shouldHoldAnythingInAViewWhenAnyBallotOfAnyTypeIsStoredInIt() {
		final BallotBox box = box();

		box.add(ballot(PREVOTE, 1, 1, A));
		box.add(ballot(PRECOMMIT, 2, 2, null));
		box.add(forged(PREVOTE, 3, 3, A));

		assertThat(box.holdsAnyIn(0), is(false));
		assertThat(box.holdsAnyIn(1), is(true));
		assertThat(box.holdsAnyIn(2), is(true));
		assertThat(box.holdsAnyIn(3), is(false));
		assertThat(box.holdsAnyIn(1_000_000), is(false));
	}

	@Example
	public void shouldAnswerNothingForAViewNobodyBalloted() {
		final BallotBox box = box();

		assertThat(box.countAny(PREVOTE, 1_000_000), equalTo(0));
		assertThat(box.quorumAny(PRECOMMIT, 1_000_000), is(false));
		assertThat(box.has(ballot(PREVOTE, 1, 1_000_000, A)), is(false));
		assertThat(box.endorsements(1_000_000, A).size(), equalTo(0));
		assertThat(box.size(), equalTo(0));
	}
}
