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
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

/* Whether the ledger has anything that needs a block is what keeps a quiet chain quiet, so it has to say the
   same as a proposal does: work when the proposer would have something to put in a block, and none otherwise */
public class LedgerWorkTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);
	private static final Mint DUE = mint(1, 700);
	private static final long CLOCK = 30_000;

	private static Ledger open(FileSystem fileSystem, String name, LedgerAuthority authority) throws IOException {
		return open(fileSystem, name, authority, CLOCK);
	}

	private static Ledger open(FileSystem fileSystem, String name, LedgerAuthority authority, long clock)
		throws IOException {
		return new Ledger(state(1000, authority, FOUNDATION),
			BlockLog.open(fileSystem.getPath("/" + name + "/blocks.log")), () -> clock);
	}

	private static void assertWork(Ledger ledger, long time, boolean expected) {
		assertThat(ledger.hasWork(time), is(expected));
		assertThat(proposal(ledger, time).isPresent(), is(expected));
	}

	@Example
	public void shouldHaveNoWorkOnAFreshLedger() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			assertWork(open(fileSystem, "a", ANY_MINT), 2000, false);
		}
	}

	@Example
	public void shouldHaveWorkOnceAValidTransferIsSubmitted() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", ANY_MINT);

			fundFirstAndThirdAccount(FOUNDATION, 2000, ledger);
			assertWork(ledger, 3000, false);

			assertThat(ledger.submit(paying(ledger, 10, 1, 0)), equalTo(Optional.empty()));
			assertWork(ledger, 3000, true);
		}
	}

	@Example
	public void shouldHaveNoWorkOnceABlockTookTheNonceOfTheOnlyTransfer() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", ANY_MINT);
			final Ledger rival = open(fileSystem, "b", ANY_MINT);

			fundFirstAndThirdAccount(FOUNDATION, 2000, ledger, rival);
			ledger.submit(paying(ledger, 10, 0, 0));
			rival.submit(paying(rival, 11, 0, 0));
			assertWork(ledger, 3000, true);

			ledger.commit(nextBlock(rival, FOUNDATION, 3000));

			assertThat(ledger.pending(), equalTo(0));
			assertWork(ledger, 3000, false);
		}
	}

	/* The transfer is queued, since the ones before it may still come, but no block can hold it yet */
	@Example
	public void shouldHaveNoWorkForATransferThatWaitsForAnEarlierNonce() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", ANY_MINT);

			fundFirstAndThirdAccount(FOUNDATION, 2000, ledger);

			assertThat(ledger.submit(paying(ledger, 10, 1, 1)), equalTo(Optional.empty()));
			assertThat(ledger.pending(), equalTo(1));
			assertWork(ledger, 3000, false);

			ledger.submit(paying(ledger, 10, 1, 0));
			assertWork(ledger, 3000, true);
		}
	}

	@Example
	public void shouldHaveWorkWhenAMintIsDueAndNotBefore() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(DUE, 20_000L)));

			assertWork(ledger, 19_999, false);
			assertWork(ledger, 20_000, true);

			ledger.commit(nextBlock(ledger, FOUNDATION, 20_000));

			assertWork(ledger, 20_000, false);
		}
	}

	/* The authority lists a mint as due but would not take it: the chain must not make empty blocks for ever */
	@Example
	public void shouldHaveNoWorkForADueMintTheAuthorityHoldsBack() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final LedgerAuthority stubborn = TestAuthority.open().withSchedule(time -> List.of(DUE))
				.withMints((mint, time) -> false);

			assertWork(open(fileSystem, "a", stubborn), 30_000, false);
		}
	}

	@Example
	public void shouldProposeOnlyTheDueMintsThatFitTheCapTogetherAndWaitForNoMoreAfterThem() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Mint first = mint(1, 5);
			final Mint second = mint(2, 6);
			final LedgerAuthority capped = TestAuthority.open().withSchedule(time -> List.of(first, second))
				.withMintsOnSupply((mint, time, supply) -> supply + mint.amount() <= 10);
			final Ledger ledger = open(fileSystem, "a", capped);

			assertThat(proposal(ledger, CLOCK).orElseThrow().getTransactions(), equalTo(List.of(first)));

			ledger.commit(nextBlock(ledger, FOUNDATION, CLOCK));

			assertThat(ledger.supply(), equalTo(5L));
			assertWork(ledger, CLOCK, false);
		}
	}

	@Example
	public void shouldProposeOnlyTheDueMintsThatFitWhatTheRecipientMayHaveAndWaitForNoMoreAfterThem()
		throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Mint first = mint(1, 6);
			final Mint second = new Mint(account(1), 5, reference(11));
			final LedgerAuthority granting = TestAuthority.open().withSchedule(time -> List.of(first, second))
				.withMintsOnTotals((mint, time, supply, minted) -> minted + mint.amount() <= 10);
			final Ledger ledger = open(fileSystem, "a", granting);

			assertThat(proposal(ledger, CLOCK).orElseThrow().getTransactions(), equalTo(List.of(first)));

			ledger.commit(nextBlock(ledger, FOUNDATION, CLOCK));

			assertThat(ledger.mintedTo(account(1)), equalTo(6L));
			assertThat(ledger.balanceOf(account(1)), equalTo(6L));
			assertWork(ledger, CLOCK, false);
		}
	}

	@Example
	public void shouldHaveNoWorkForATransferBelowTheFeeTheNodeNowDemands() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong least = new AtomicLong(0);
			final LedgerAuthority demanding = TestAuthority.open()
				.withTransfers(transfer -> transfer.fee() >= least.get());
			final Ledger ledger = open(fileSystem, "a", demanding);

			fundFirstAndThirdAccount(FOUNDATION, 2000, ledger);
			ledger.submit(paying(ledger, 10, 1, 0));
			assertWork(ledger, 3000, true);

			least.set(5);

			assertWork(ledger, 3000, false);
		}
	}

	@Example
	public void shouldHaveWorkWhileFeesWaitAndNoneOnceABlockPaidThem() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", ANY_MINT);

			fundFirstAndThirdAccount(FOUNDATION, 2000, ledger);
			ledger.submit(paying(ledger, 10, 10, 0));
			ledger.commit(nextBlock(ledger, FOUNDATION, 3000));

			assertThat(ledger.pending(), equalTo(0));
			assertWork(ledger, 3000, true);

			ledger.commit(nextBlock(ledger, FOUNDATION, 3000));

			assertWork(ledger, 3000, false);
		}
	}

	/* A ledger with no fee to pay out, nothing waiting and no mint due says no without looking at any block, and each
	   of the three alone is enough to make it look */
	@Example
	public void shouldLookForWorkWhenAnyOneOfFeesTransactionsAndMintsIsThere() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger quiet = open(fileSystem, "quiet", dueAt(Map.of(DUE, 20_000L)));
			final Ledger fees = open(fileSystem, "fees", ANY_MINT);
			final Ledger waiting = open(fileSystem, "waiting", ANY_MINT);

			assertWork(quiet, 19_999, false);
			assertWork(quiet, 20_000, true);

			fundFirstAndThirdAccount(FOUNDATION, 2000, fees);
			fees.submit(paying(fees, 10, 10, 0));
			fees.commit(nextBlock(fees, FOUNDATION, 3000));
			assertThat(fees.pending(), equalTo(0));
			assertWork(fees, 3000, true);

			fundFirstAndThirdAccount(FOUNDATION, 2000, waiting);
			assertWork(waiting, 3000, false);
			waiting.submit(paying(waiting, 10, 0, 0));
			assertWork(waiting, 3000, true);
		}
	}

	/* The time work is asked about is for the block that would be made: what the node takes in is still judged at
	   its own clock, so a mint that is due at that later time but not yet by the clock is refused */
	@Example
	public void shouldTakeInOnlyWhatTheClockAllowsWhateverTimeWorkWasAskedAt() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(DUE, 20_000L)), 10_000);

			assertWork(ledger, 30_000, true);

			assertThat(ledger.submit(DUE).orElse(""), containsString("not authorized"));
			assertThat(ledger.pending(), equalTo(0));
		}
	}

	/* What waits in the mempool is checked again at the clock after a commit, not at the time work was last asked
	   about, which would drop a mint that the authority only allows until a time the clock has not reached */
	@Example
	public void shouldCheckTheWaitingAtTheClockAfterACommitWhateverTimeWorkWasAskedAt() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final LedgerAuthority untilThen = mintsWhen((mint, time) -> time <= 25_000);
			final Ledger ledger = open(fileSystem, "a", untilThen, 10_000);
			final Block other = block(state(1000, ANY_MINT, FOUNDATION), FOUNDATION, FOUNDATION.size(), 10_000,
				List.of(mint(1, 5)));

			ledger.submit(mint(2, 5));
			ledger.submit(mint(3, 5));
			assertThat(ledger.hasWork(30_000), is(false));

			ledger.commit(other);

			assertThat(ledger.pending(), equalTo(2));
		}
	}

	/* The block takes the time of the tip when given an earlier one, so work is judged at that time too */
	@Example
	public void shouldJudgeTheWorkAtTheTimeOfTheTipWhenGivenAnEarlierOne() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Mint gated = mint(2, 5);
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(gated, 40_000L)));

			ledger.commit(block(state(1000, ANY_MINT, FOUNDATION), FOUNDATION, FOUNDATION.size(), 40_000,
				List.of(mint(3, 5))));

			assertWork(ledger, 10_000, true);
		}
	}

	@Example
	public void shouldLeaveTheMempoolAsItWasWhenAskingForWork() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", ANY_MINT);

			ledger.submit(mint(1, 5));
			ledger.submit(mint(2, 5));

			final Optional<Block> before = proposal(ledger, 2000);

			assertThat(ledger.hasWork(2000), is(true));
			assertThat(ledger.hasWork(2000), is(true));
			assertThat(ledger.pending(), equalTo(2));
			assertThat(proposal(ledger, 2000), equalTo(before));
		}
	}
}
