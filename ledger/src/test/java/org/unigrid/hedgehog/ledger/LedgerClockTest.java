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
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerClockTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);
	private static final Mint DUE = mint(1, 700);

	private static Ledger open(FileSystem fileSystem, String name, LedgerAuthority authority, AtomicLong clock)
		throws IOException {
		return new Ledger(state(1000, authority, FOUNDATION),
			BlockLog.open(fileSystem.getPath("/" + name + "/blocks.log")), clock::get);
	}

	private static Optional<Block> proposal(Ledger ledger, long time) {
		return FOUNDATION.stream().map(seed -> ledger.propose(seed, time)).flatMap(Optional::stream).findFirst();
	}

	private static Block certified(Ledger ledger, Block proposed) {
		Block block = proposed;

		for (final byte[] seed : FOUNDATION) {
			if (!block.getEndorsements().stream().anyMatch(e -> e.signer().equals(Ed25519.publicKey(seed)))) {
				block = ledger.sign(block, seed);
			}
		}

		return block;
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

			final Block block = proposal(ledger, 20_000).orElseThrow();

			assertThat(block.getTransactions(), equalTo(List.of(DUE)));
		}
	}

	@Example
	public void shouldMakeADueMintOnceAndThenProposeNothing() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(30_000);
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(DUE, 20_000L)), clock);
			ledger.commit(certified(ledger, proposal(ledger, 30_000).orElseThrow()));

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

			assertThat(proposal(ledger, 30_000).orElseThrow().getTransactions(), equalTo(List.of(DUE, waiting)));
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

			final Block next = proposal(ledger, 10_000).orElseThrow();

			assertThat(next.getTime(), equalTo(40_000L));
			assertThat(next.getTransactions(), equalTo(List.of(gated)));
			assertThat(ledger.sign(next, seedOtherThanTheProposerOf(next)).getEndorsements().size(), equalTo(2));
		}
	}

	@Example
	public void shouldApplyADueMintThatAlsoWaitsInTheMempoolOnce() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(DUE, 20_000L)), new AtomicLong(30_000));

			ledger.submit(DUE);

			final Block block = proposal(ledger, 30_000).orElseThrow();

			assertThat(block.getTransactions(), equalTo(List.of(DUE)));

			ledger.commit(certified(ledger, block));

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
			ledger.commit(certified(ledger, proposal(ledger, 15_000).orElseThrow()));

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

			final Block block = proposal(ledger, 30_000).orElseThrow();

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
			final Block first = proposal(ledger, 30_000).orElseThrow();

			assertThat(first.getTransactions(),
				equalTo(List.<Transaction>copyOf(backlog.subList(0, Block.MAX_TRANSACTIONS))));

			ledger.commit(certified(ledger, first));

			assertThat(proposal(ledger, 30_000).orElseThrow().getTransactions(),
				equalTo(List.<Transaction>copyOf(backlog.subList(Block.MAX_TRANSACTIONS, backlog.size()))));
		}
	}

	@Example
	public void shouldNotSignABlockDatedMoreThanTheSkewAheadOfTheClock() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(100_000);
			final Ledger ledger = open(fileSystem, "a", ANY_MINT, clock);

			ledger.submit(mint(1, 5));

			final Block atTheLimit = proposal(ledger, 100_000 + Ledger.MAX_CLOCK_SKEW_MILLIS).orElseThrow();

			assertThat(ledger.sign(atTheLimit, seedOtherThanTheProposerOf(atTheLimit)).getEndorsements().size(),
				equalTo(2));

			final Ledger other = open(fileSystem, "b", ANY_MINT, clock);

			other.submit(mint(1, 5));

			final Block tooLate = proposal(other, 100_000 + Ledger.MAX_CLOCK_SKEW_MILLIS + 1).orElseThrow();
			final InvalidBlockException refused = assertThrows(InvalidBlockException.class,
				() -> other.sign(tooLate, seedOtherThanTheProposerOf(tooLate)));

			assertThat(refused.getMessage(), containsString("ahead"));
		}
	}

	/* The block's own time makes the mint due when the block is checked, so only the clock stands between a
	   proposer that dates its block ahead and a mint that is not due yet */
	@Example
	public void shouldNotSignAMintThatIsNotDueAtTheClockInABlockDatedBeyondTheSkew() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong clock = new AtomicLong(100_000);
			final Ledger ledger = open(fileSystem, "a", dueAt(Map.of(DUE, 200_000L)), clock);
			final Block early = block(state(1000, ANY_MINT, FOUNDATION), FOUNDATION, 1, 200_000, List.of(DUE));
			final byte[] validator = seedOtherThanTheProposerOf(early);
			final InvalidBlockException refused = assertThrows(InvalidBlockException.class,
				() -> ledger.sign(early, validator));

			assertThat(refused.getMessage(), containsString("ahead"));

			clock.set(169_999);

			assertThrows(InvalidBlockException.class, () -> ledger.sign(early, validator));

			clock.set(170_000);

			assertThat(ledger.sign(early, validator).getEndorsements().size(), equalTo(2));
		}
	}

	@Example
	public void shouldCheckTheClockBeforeTheContentOfTheBlock() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", NO_MINT, new AtomicLong(100_000));
			final Block early = block(state(1000, ANY_MINT, FOUNDATION), FOUNDATION, 1, 200_000,
				List.of(mint(1, 5)));
			final InvalidBlockException refused = assertThrows(InvalidBlockException.class,
				() -> ledger.sign(early, seedOtherThanTheProposerOf(early)));

			assertThat(refused.getMessage(), containsString("ahead"));
		}
	}

	@Example
	public void shouldNeverRefuseACertifiedBlockForItsTime() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final AtomicLong maker = new AtomicLong(1_000_000);
			final Ledger far = open(fileSystem, "a", ANY_MINT, maker);

			far.submit(mint(1, 5));

			final Block block = certified(far, proposal(far, 1_000_000).orElseThrow());
			final Ledger behind = open(fileSystem, "b", ANY_MINT, new AtomicLong(1));

			behind.commit(block);

			assertThat(behind.balanceOf(account(1)), equalTo(5L));
		}
	}

	private static byte[] seedOtherThanTheProposerOf(Block block) {
		return FOUNDATION.stream().filter(seed -> !Ed25519.publicKey(seed).equals(block.getProposer())).findFirst()
			.orElseThrow();
	}
}
