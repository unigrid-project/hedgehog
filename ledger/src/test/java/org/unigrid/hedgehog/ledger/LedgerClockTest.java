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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerClockTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);
	private static final Mint DUE = mint(1, 700);

	private static Ledger open(FileSystem fileSystem, String name, LedgerAuthority authority, AtomicLong clock)
		throws IOException {
		return new Ledger(state(1000, authority, FOUNDATION),
			BlockLog.open(fileSystem.getPath("/" + name + "/blocks.log")), clock::get);
	}

	private static List<Mint> mints(int from, int count) {
		return IntStream.range(from, from + count).mapToObj(n -> mint(n, 1)).toList();
	}

	@Example
	public void shouldProposeADueMintOnlyOnceItsTimeHasCome() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(10_000);
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(DUE, 20_000L)), clock);

			assertThat(proposal(ledger, 19_999).isPresent(), is(false));

			final Block block = proposed(ledger, 20_000);

			assertThat(block.getTransactions(), equalTo(List.of(DUE)));
		}
	}

	@Example
	public void shouldMakeADueMintOnceAndThenProposeNothing() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(30_000);
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(DUE, 20_000L)), clock);
			ledger.commit(nextBlock(ledger, FOUNDATION, 30_000));

			assertThat(ledger.balanceOf(account(1)), equalTo(700L));
			assertThat(proposal(ledger, 40_000).isPresent(), is(false));
		}
	}

	/* The authority lists a mint as due but would not take it: it must not count as work, or the chain would
	   propose empty blocks for ever */
	@Example
	public void shouldNotCountADueMintTheAuthorityWillNotTake() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(30_000);
			final LedgerAuthority stubborn = TestAuthority.open().withSchedule(time -> List.of(DUE))
				.withMints((mint, time) -> false);
			final Ledger ledger = open(fileSystem, "a", stubborn, clock);

			assertThat(proposal(ledger, 30_000).isPresent(), is(false));
		}
	}

	@Example
	public void shouldTakeADueMintIntoTheMempoolOnlyAtTheTimeOfTheClock() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(10_000);
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(DUE, 20_000L)), clock);

			assertThat(ledger.submit(DUE).orElse(""), containsString("not authorized"));

			clock.set(20_000);

			assertThat(ledger.submit(DUE), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldPutTheDueMintsBeforeWhatWaitsInTheMempool() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Mint waiting = mint(2, 5);
			final LedgerAuthority due = TestAuthority.open().withSchedule(time -> List.of(DUE));
			final Ledger ledger = open(fileSystem, "a", due, new AtomicLong(30_000));

			ledger.submit(waiting);

			assertThat(proposed(ledger, 30_000).getTransactions(), equalTo(List.of(DUE, waiting)));
		}
	}

	/* An earlier time than the tip's is lifted to it, and the due mints are listed, checked and sealed at that
	   lifted time: a mint due exactly then is proposed, in a block that carries that time */
	@Example
	public void shouldProposeAtTheTimeOfTheTipWhenGivenAnEarlierOne() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Mint gated = mint(2, 5);
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(gated, 40_000L)), new AtomicLong(100_000));

			ledger.commit(block(state(1000, ANY_MINT, FOUNDATION), FOUNDATION, FOUNDATION.size(), 40_000,
				List.of(mint(3, 5))));

			final Block next = proposed(ledger, 10_000);

			assertThat(next.getTime(), equalTo(40_000L));
			assertThat(next.getTransactions(), equalTo(List.of(gated)));
			assertThat(ledger.validate(next), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldApplyADueMintThatAlsoWaitsInTheMempoolOnce() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(DUE, 20_000L)), new AtomicLong(30_000));

			ledger.submit(DUE);

			final Block block = proposed(ledger, 30_000);

			assertThat(block.getTransactions(), equalTo(List.of(DUE)));

			ledger.commit(certify(block, ledger, FOUNDATION));

			assertThat(ledger.balanceOf(account(1)), equalTo(700L));
			assertThat(ledger.pending(), equalTo(0));
		}
	}

	/* A block proposed for a time before the clock leaves that time with the state, but what waits in the mempool
	   is checked again at the time of the node */
	@Example
	public void shouldCheckTheWaitingAgainAtTheClockAfterACommit() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Mint earlier = mint(2, 5);
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(DUE, 20_000L, earlier, 10_000L)),
				new AtomicLong(20_000));

			ledger.submit(DUE);
			ledger.commit(nextBlock(ledger, FOUNDATION, 15_000));

			assertThat(ledger.pending(), equalTo(1));
		}
	}

	/* The cap on a block comes after the checks: what the authority holds back must not take the places of what can
	   go in */
	@Example
	public void shouldFillTheBlockFromTheMempoolWhateverTheAuthorityHoldsBack() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Mint refused = mint(5000, 1);
			final List<Mint> waiting = mints(1, Block.MAX_TRANSACTIONS);
			final LedgerAuthority holdingBack = TestAuthority.open().withSchedule(time -> List.of(refused))
				.withMints((mint, time) -> !mint.equals(refused));
			final Ledger ledger = open(fileSystem, "a", holdingBack, new AtomicLong(30_000));

			waiting.forEach(ledger::submit);

			final Block block = proposed(ledger, 30_000);

			assertThat(block.getTransactions(), equalTo(List.<Transaction>copyOf(waiting)));
		}
	}

	@Example
	public void shouldCutTheBlockAtTheLimitAndLeaveTheRestOfTheDueMintsForTheNext() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final List<Mint> backlog = mints(1, Block.MAX_TRANSACTIONS + 5);
			final Map<Mint, Long> dueTimes = IntStream.range(0, backlog.size()).boxed()
				.collect(Collectors.toMap(backlog::get, i -> 10_000L + i));
			final Ledger ledger = open(fileSystem, "a", dueAt(dueTimes), new AtomicLong(30_000));
			final Block first = proposed(ledger, 30_000);

			assertThat(first.getTransactions(),
				equalTo(List.<Transaction>copyOf(backlog.subList(0, Block.MAX_TRANSACTIONS))));

			ledger.commit(certify(first, ledger, FOUNDATION));

			assertThat(proposed(ledger, 30_000).getTransactions(),
				equalTo(List.<Transaction>copyOf(backlog.subList(Block.MAX_TRANSACTIONS, backlog.size()))));
		}
	}

	@Example
	public void shouldNotValidateABlockDatedMoreThanTheSkewAheadOfTheClock() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(100_000);
			final Ledger ledger = open(fileSystem, "a", ANY_MINT, clock);

			ledger.submit(mint(1, 5));

			final Block atTheLimit = proposed(ledger, 100_000 + Ledger.MAX_CLOCK_SKEW_MILLIS);

			assertThat(ledger.validate(atTheLimit), equalTo(Optional.empty()));

			final Ledger other = open(fileSystem, "b", ANY_MINT, clock);

			other.submit(mint(1, 5));

			final Block tooLate = proposed(other, 100_000 + Ledger.MAX_CLOCK_SKEW_MILLIS + 1);

			assertThat(other.validate(tooLate).orElse(""), containsString("ahead"));
		}
	}

	/* The block's own time makes the mint due when the block is checked, so only the clock stands between a
	   proposer that dates its block ahead and a mint that is not due yet: a validator accepts it up to the skew
	   before its time by the clock of the validator, and not earlier */
	@Example
	public void shouldValidateAMintUpToTheSkewBeforeItsTimeAndNotEarlier() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(100_000);
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(DUE, 200_000L)), clock);
			final Block early = block(state(1000, ANY_MINT, FOUNDATION), FOUNDATION, 1, 200_000, List.of(DUE));

			assertThat(ledger.validate(early).orElse(""), containsString("ahead"));

			clock.set(169_999);

			assertThat(ledger.validate(early).orElse(""), containsString("ahead"));

			clock.set(170_000);

			assertThat(ledger.validate(early), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldCheckTheClockBeforeTheContentOfTheBlock() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", NO_MINT, new AtomicLong(100_000));
			final Block early = block(state(1000, ANY_MINT, FOUNDATION), FOUNDATION, 1, 200_000,
				List.of(mint(1, 5)));

			assertThat(ledger.validate(early).orElse(""), containsString("ahead"));
		}
	}

	@Example
	public void shouldNeverRefuseACertifiedBlockForItsTime() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong maker = new AtomicLong(1_000_000);
			final Ledger far = open(fileSystem, "a", ANY_MINT, maker);

			far.submit(mint(1, 5));

			final Block block = nextBlock(far, FOUNDATION, 1_000_000);
			final Ledger behind = open(fileSystem, "b", ANY_MINT, new AtomicLong(1));

			assertThat(behind.validateCertified(block), equalTo(Optional.empty()));

			behind.commit(block);

			assertThat(behind.balanceOf(account(1)), equalTo(5L));
		}
	}

	@Example
	public void shouldTakeProposeAndValidateATransferAtTheMinimumFeeButNotOneBelowIt() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(10_000);
			final Ledger lenient = open(fileSystem, "a", ANY_MINT, clock);
			final Ledger picky = open(fileSystem, "b", minimumFee(5), clock);

			fundFirstAndThirdAccount(FOUNDATION, 10_000, lenient, picky);

			final Transfer enough = paying(picky, 10, 5, 0);

			assertThat(picky.submit(paying(picky, 10, 4, 0)).orElse(""), containsString("Fee is below"));
			assertThat(picky.pending(), equalTo(0));
			assertThat(picky.submit(enough), equalTo(Optional.empty()));

			final Block block = proposed(picky, 11_000);

			assertThat(block.getTransactions(), equalTo(List.of(enough)));
			assertThat(picky.validate(block), equalTo(Optional.empty()));
		}
	}

	/* The fee a node demands is its own policy, so a block that the validators signed is applied whatever the fee */
	@Example
	public void shouldNotValidateATransferBelowTheMinimumFeeButApplyItInACertifiedBlock() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(10_000);
			final Ledger lenient = open(fileSystem, "a", ANY_MINT, clock);
			final Ledger picky = open(fileSystem, "b", minimumFee(5), clock);

			fundFirstAndThirdAccount(FOUNDATION, 10_000, lenient, picky);

			assertThat(lenient.submit(paying(lenient, 10, 4, 0)), equalTo(Optional.empty()));

			final Block block = proposed(lenient, 11_000);

			assertThat(picky.validate(block).orElse(""), containsString("Fee is below"));

			picky.commit(certify(block, lenient, FOUNDATION));

			assertThat(picky.balanceOf(account(2)), equalTo(10L));
		}
	}

	/* The fee a node demands can rise while a transfer waits in its mempool, so the proposer asks again */
	@Example
	public void shouldLeaveOutOfItsBlockATransferThatNoLongerPaysTheFeeTheNodeDemands() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong least = new AtomicLong(0);
			final LedgerAuthority demanding = TestAuthority.open()
				.withTransfers(transfer -> transfer.fee() >= least.get());
			final Ledger ledger = open(fileSystem, "a", demanding, new AtomicLong(10_000));

			fundFirstAndThirdAccount(FOUNDATION, 10_000, ledger);

			final Transfer enough = Transfer.signed(accountSeed(3), account(2), 10, 5, 0, ledger.chainId());

			assertThat(ledger.submit(paying(ledger, 10, 1, 0)), equalTo(Optional.empty()));
			assertThat(ledger.submit(enough), equalTo(Optional.empty()));

			least.set(5);

			assertThat(proposed(ledger, 11_000).getTransactions(), equalTo(List.of(enough)));
		}
	}

	/* The blocks in the log are certified and stand whatever the clock says when the node starts again */
	@Example
	public void shouldRestoreADueMintFromTheLogAtAnyClock() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final LedgerAuthority gated = dueAt(Map.of(DUE, 20_000L));
			final Ledger first = open(fileSystem, "a", gated, new AtomicLong(30_000));

			first.commit(nextBlock(first, FOUNDATION, 30_000));

			for (final long restartedAt : new long[] { 1, 10_000_000 }) {
				final Ledger restarted = open(fileSystem, "a", gated, new AtomicLong(restartedAt));

				assertThat(restarted.height(), equalTo(1L));
				assertThat(restarted.balanceOf(account(1)), equalTo(700L));
				assertThat(proposal(restarted, restartedAt).isPresent(), is(false));
			}
		}
	}
}
