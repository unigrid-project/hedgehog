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

import java.io.IOException;
import java.nio.file.FileSystem;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

/* The round a node is in is the round of its clock, as the next block it makes is dated by that clock: it is the
   round a vote is signed for, the one a vote it takes in must name, and the one a vote that waits must still be in */
public class LedgerClockRoundTest {
	private static final long ROUND = 60_000;
	private static final List<byte[]> FOUNDATION = seeds(1, 3);
	private static final byte[] GRIDNODE = seed(201);

	private static long startOf(long round) {
		return GENESIS_TIME + round * ROUND;
	}

	private static Ledger open(FileSystem fileSystem, AtomicLong clock) throws IOException {
		return new Ledger(state(ROUND, ANY_MINT, FOUNDATION), BlockLog.open(fileSystem.getPath("/data/blocks.log")),
			clock::get);
	}

	private static Vote add(Ledger ledger, byte[] voter, byte[] candidate, long round) {
		return Vote.signed(voter, Ed25519.publicKey(candidate), Vote.Action.ADD, round, ledger.chainId());
	}

	private static void seal(Ledger ledger, long time) throws IOException {
		ledger.commit(nextBlock(ledger, FOUNDATION, ledger.validators().size(), time));
	}

	@Example
	public void shouldBeInTheRoundOfItsClock() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(3) + 5);
			final Ledger ledger = open(fileSystem, clock);

			assertThat(ledger.round(), equalTo(3L));
			clock.set(startOf(4));
			assertThat(ledger.round(), equalTo(4L));
		}
	}

	/* No block is dated before its tip, so a clock that runs behind the tip is in the round of the tip */
	@Example
	public void shouldNeverBeInARoundBeforeTheTip() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(2));
			final Ledger ledger = open(fileSystem, clock);

			ledger.submit(mint(1, 5));
			seal(ledger, clock.get());
			clock.set(startOf(1));

			assertThat(ledger.round(), equalTo(2L));
		}
	}

	@Example
	public void shouldTakeOnlyAVoteForTheRoundOfItsClock() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(2) + 1);
			final Ledger ledger = open(fileSystem, clock);

			assertThat(ledger.submit(add(ledger, seed(1), GRIDNODE, 2)), equalTo(Optional.empty()));
			assertThat(ledger.submit(add(ledger, seed(2), GRIDNODE, 0)).orElse(""), containsString("another round"));
		}
	}

	/* The next block closes the round of the tip before anything else, so the set it elects already counts for a vote
	   taken in now */
	@Example
	public void shouldJudgeAVoteByTheSetThatTheRoundThatPassedElected() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(0) + 1);
			final Ledger ledger = withVotesInRoundZero(fileSystem, clock);

			clock.set(startOf(1));

			assertThat(ledger.validators().contains(Ed25519.publicKey(GRIDNODE)), is(false));
			assertThat(ledger.submit(add(ledger, GRIDNODE, seed(202), 1)), equalTo(Optional.empty()));
			assertThat(ledger.submit(add(ledger, seed(1), GRIDNODE, 1)).orElse(""),
				containsString("already a validator"));
		}
	}

	@Example
	public void shouldNeverProposeAVoteWhoseRoundHasPassed() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(0) + 1);
			final Ledger ledger = open(fileSystem, clock);

			ledger.submit(add(ledger, seed(1), GRIDNODE, 0));
			ledger.submit(mint(1, 5));
			clock.set(startOf(1));

			assertThat(proposed(ledger, clock.get()).getTransactions(), equalTo(List.of(mint(1, 5))));
			assertThat(ledger.pending(), equalTo(1));
		}
	}

	/* A vote that missed the end of its round could only be refused by every block from then on */
	@Example
	public void shouldDropAWaitingVoteOnceItsRoundHasPassed() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(0) + 1);
			final Ledger ledger = open(fileSystem, clock);

			assertThat(ledger.submit(add(ledger, seed(1), GRIDNODE, 0)), equalTo(Optional.empty()));
			clock.set(startOf(1));

			assertThat(ledger.hasWork(clock.get()), is(false));
			assertThat(ledger.pending(), equalTo(0));
		}
	}

	/* The foundation votes the gridnode in, in a block of round 0 */
	private static Ledger withVotesInRoundZero(FileSystem fileSystem, AtomicLong clock) throws IOException {
		final Ledger ledger = open(fileSystem, clock);

		for (final byte[] voter : FOUNDATION) {
			ledger.submit(add(ledger, voter, GRIDNODE, 0));
		}

		seal(ledger, clock.get());
		return ledger;
	}

	@Example
	public void shouldHaveWorkOnceTheTimeOfARoundThatHoldsVotesIsOver() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = withVotesInRoundZero(fileSystem, new AtomicLong(startOf(0) + 1));

			assertThat(ledger.hasWork(startOf(1) - 1), is(false));
			assertThat(ledger.hasWork(startOf(1)), is(true));
		}
	}

	/* Nothing waits, so the block that closes the round is empty, dated by the clock of its proposer */
	@Example
	public void shouldProposeTheEmptyBlockThatClosesTheRoundAndNoMore() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(0) + 1);
			final Ledger ledger = withVotesInRoundZero(fileSystem, clock);

			clock.set(startOf(1) + 7);

			final Block closing = proposed(ledger, clock.get());

			assertThat(closing.getTransactions(), is(empty()));
			assertThat(closing.getTime(), equalTo(startOf(1) + 7));
			assertThat(ledger.validate(closing), equalTo(Optional.empty()));
			ledger.commit(certify(closing, ledger, FOUNDATION));
			assertThat(ledger.validators().contains(Ed25519.publicKey(GRIDNODE)), is(true));
			assertThat(ledger.hasWork(clock.get()), is(false));
			assertThat(proposal(ledger, startOf(3)), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldHaveNoWorkWhenARoundWithoutVotesEnds() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(0) + 1);
			final Ledger ledger = open(fileSystem, clock);

			ledger.submit(mint(1, 5));
			seal(ledger, clock.get());

			assertThat(ledger.hasWork(startOf(1)), is(false));
			assertThat(proposal(ledger, startOf(5)), equalTo(Optional.empty()));
		}
	}

	/* A validator judges the time of the block that closes a round as it judges any other */
	@Example
	public void shouldRefuseABlockThatClosesTheRoundWhenItIsDatedTooFarAheadOfItsClock() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(0) + 1);
			final Ledger ledger = withVotesInRoundZero(fileSystem, clock);

			clock.set(startOf(1) - Ledger.MAX_CLOCK_SKEW_MILLIS - 1);

			assertThat(ledger.validate(proposed(ledger, startOf(1))).orElse(""), containsString("ahead"));
		}
	}

	/* Opening the next round takes a copy of the state, which a peer must not be able to make the node pay for with
	   each transaction it sends: the opened state is made once and kept until the round or the tip moves */
	@Example
	public void shouldOpenTheNextRoundOnceAndNotForEachTransactionItTakesIn() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(1) + 1);
			final Ledger ledger = open(fileSystem, clock);
			final LedgerState opened = ledger.judging();

			for (int n = 1; n <= 20; n++) {
				assertThat(ledger.submit(mint(n, 5)), equalTo(Optional.empty()));
				assertThat(ledger.submit(add(ledger, seed(1), seed(300 + n), 1)), equalTo(Optional.empty()));
			}

			assertThat(ledger.judging(), sameInstance(opened));
			clock.set(startOf(2));
			assertThat(ledger.judging(), not(sameInstance(opened)));
		}
	}

	/* The round and the signature of a vote need nothing of the state, so they are checked before anything that does */
	@Example
	public void shouldCheckTheRoundAndTheSignatureOfAVoteBeforeWhoCastIt() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(1) + 1);
			final Ledger ledger = open(fileSystem, clock);
			final Vote outsider = add(ledger, seed(99), GRIDNODE, 1);
			final Vote forged = new Vote(outsider.voter(), outsider.candidate(), outsider.action(), outsider.round(),
				new byte[Ed25519.SIGNATURE_SIZE]);

			assertThat(ledger.submit(add(ledger, seed(99), GRIDNODE, 0)).orElse(""), containsString("another round"));
			assertThat(ledger.submit(forged).orElse(""), containsString("signature"));
			assertThat(ledger.submit(outsider).orElse(""), containsString("not a validator"));
		}
	}

	/* A proposer whose clock runs behind may date its block in the round this node's clock has left; a vote taken for
	   the round of this clock still waits for a block of that round */
	@Example
	public void shouldKeepAVoteForTheRoundOfItsClockWhenABlockOfAnEarlierRoundComes() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(startOf(1) + 10);
			final Ledger ledger = open(fileSystem, clock);
			final Vote vote = add(ledger, seed(1), GRIDNODE, 1);

			assertThat(ledger.submit(vote), equalTo(Optional.empty()));
			ledger.submit(mint(1, 5));
			seal(ledger, startOf(1) - 1);

			assertThat(ledger.pending(), equalTo(1));
			assertThat(proposed(ledger, clock.get()).getTransactions(), equalTo(List.of(vote)));
		}
	}
}
