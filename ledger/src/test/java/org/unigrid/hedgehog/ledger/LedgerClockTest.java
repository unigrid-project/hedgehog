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
			Block block = proposal(ledger, 30_000).orElseThrow();

			for (final byte[] seed : FOUNDATION) {
				if (!block.getEndorsements().stream().anyMatch(e -> e.signer().equals(Ed25519.publicKey(seed)))) {
					block = ledger.sign(block, seed);
				}
			}

			ledger.commit(block);

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

			Block block = proposal(far, 1_000_000).orElseThrow();

			for (final byte[] seed : FOUNDATION) {
				if (!block.getEndorsements().stream().anyMatch(e -> e.signer().equals(Ed25519.publicKey(seed)))) {
					block = far.sign(block, seed);
				}
			}

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
