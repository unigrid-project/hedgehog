/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);

	private static Ledger open(FileSystem fileSystem, int roundLength, MintAuthority authority) throws IOException {
		return new Ledger(state(roundLength, authority, FOUNDATION),
			BlockLog.open(fileSystem.getPath("/data/blocks.log")));
	}

	private static Ledger open(FileSystem fileSystem) throws IOException {
		return open(fileSystem, 1000, ANY_MINT);
	}

	private static byte[] chainId() {
		return state(1000, ANY_MINT, FOUNDATION).chainId();
	}

	/* What the engine will do over the network, done in one place: whoever is due proposes, then the other
	   validators of the set add their signatures, and the block is committed */
	private static Block seal(Ledger ledger, List<byte[]> everyone, long time) throws IOException {
		Block block = null;

		for (final byte[] seed : everyone) {
			final Optional<Block> proposed = ledger.propose(seed, time);

			if (proposed.isPresent()) {
				block = proposed.get();
				break;
			}
		}

		if (block == null) {
			throw new AssertionError("Nobody had anything to propose");
		}

		for (final byte[] seed : everyone) {
			final AccountKey signer = Ed25519.publicKey(seed);
			final boolean signed = block.getEndorsements().stream().anyMatch(e -> e.signer().equals(signer));

			if (ledger.validators().contains(signer) && !signed) {
				block = ledger.sign(block, seed);
			}
		}

		ledger.commit(block);
		return block;
	}

	private static Vote voteAdd(Ledger ledger, int voter, byte[] candidate, long round) {
		return Vote.signed(seed(voter), Ed25519.publicKey(candidate), Vote.Action.ADD, round, ledger.chainId());
	}

	@Example
	public void shouldTurnASubmittedMintIntoABalance() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			assertThat(ledger.submit(mint(1, 500)), equalTo(Optional.empty()));
			assertThat(ledger.pending(), equalTo(1));

			seal(ledger, FOUNDATION, 2000);

			assertThat(ledger.height(), equalTo(1L));
			assertThat(ledger.balanceOf(account(1)), equalTo(500L));
			assertThat(ledger.pending(), equalTo(0));
		}
	}

	/* Blocks come when they are needed, never on a timer, so an idle chain proposes nothing at all */
	@Example
	public void shouldProposeNothingWhenNothingIsPending() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			for (final byte[] seed : FOUNDATION) {
				assertThat(ledger.propose(seed, 2000), equalTo(Optional.empty()));
			}
		}
	}

	@Example
	public void shouldLetOnlyTheProposerOfTheHeightPropose() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			assertThat(ledger.propose(seed(2), 2000), equalTo(Optional.empty()));
			assertThat(ledger.propose(seed(3), 2000), equalTo(Optional.empty()));
			assertThat(ledger.propose(seed(99), 2000), equalTo(Optional.empty()));
			assertThat(ledger.propose(seed(1), 2000).isPresent(), is(true));
		}
	}

	@Example
	public void shouldProposeWithOnlyTheProposersOwnSignature() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final Block proposed = ledger.propose(seed(1), 2000).get();

			assertThat(proposed.getEndorsements().size(), equalTo(1));
			assertThat(proposed.getEndorsements().get(0).signer(), equalTo(key(1)));
		}
	}

	@Example
	public void shouldNotCommitABlockWithoutAQuorumAndLogNothing() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final Block proposed = ledger.propose(seed(1), 2000).get();

			assertThrows(InvalidBlockException.class, () -> ledger.commit(proposed));
			assertThat(ledger.height(), equalTo(0L));
			assertThat(open(fileSystem).height(), equalTo(0L));
		}
	}

	/* A signature is a promise that the block is right, so a validator signs nothing it has not checked */
	@Example
	public void shouldRefuseToSignABlockThatIsNotValid() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final Block proposed = ledger.propose(seed(1), 2000).get();
			final Block wrongRoot = proposed.toBuilder().stateRoot(new byte[Digests.HASH_SIZE]).build();
			final Block wrongTransactions = proposed.toBuilder().transactions(List.of(mint(1, 999))).build();

			assertThrows(InvalidBlockException.class, () -> ledger.sign(wrongRoot, seed(2)));
			assertThrows(InvalidBlockException.class, () -> ledger.sign(wrongTransactions, seed(2)));
			assertThat(ledger.sign(proposed, seed(2)).getEndorsements().size(), equalTo(2));
		}
	}

	@Example
	public void shouldRefuseToSignWithAKeyThatIsNoValidator() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final Block proposed = ledger.propose(seed(1), 2000).get();

			assertThrows(IllegalArgumentException.class, () -> ledger.sign(proposed, seed(99)));
		}
	}

	/* Signing two blocks at one height is how a validator splits the chain, so this one never does it */
	@Example
	public void shouldNeverSignTwoDifferentBlocksAtOneHeight() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final Block first = ledger.propose(seed(1), 2000).get();
			final Block second = first.toBuilder().time(2001).build();

			assertThat(ledger.sign(first, seed(2)).getEndorsements().size(), equalTo(2));
			assertThat(ledger.sign(first, seed(2)).getEndorsements().size(), equalTo(2));
			assertThrows(IllegalStateException.class, () -> ledger.sign(second, seed(2)));
			assertThrows(IllegalStateException.class, () -> ledger.sign(second, seed(1)));
		}
	}

	@Example
	public void shouldLetAValidatorSignAgainAtTheNextHeight() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));
			seal(ledger, FOUNDATION, 2000);
			ledger.submit(mint(2, 500));

			assertThat(seal(ledger, FOUNDATION, 2001).getHeight(), equalTo(2L));
		}
	}

	@Example
	public void shouldNotProposeInThePast() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));
			assertThat(ledger.propose(seed(1), 5).get().getTime(), equalTo(1000L));
		}
	}

	@Example
	public void shouldRefuseWhatItCannotUse() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, 1000, NO_MINT);

			assertThat(ledger.submit(mint(1, 500)).isPresent(), is(true));
			assertThat(ledger.pending(), equalTo(0));
		}
	}

	@Example
	public void shouldRefuseTheSameTransactionTwice() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			assertThat(ledger.submit(mint(1, 500)), equalTo(Optional.empty()));
			assertThat(ledger.submit(mint(1, 500)).isPresent(), is(true));
			assertThat(ledger.pending(), equalTo(1));
		}
	}

	@Example
	public void shouldRestoreItsStateFromTheLog() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger first = open(fileSystem);

			first.submit(mint(1, 500));
			seal(first, FOUNDATION, 2000);

			final Ledger second = open(fileSystem);

			assertThat(second.height(), equalTo(1L));
			assertThat(second.balanceOf(account(1)), equalTo(500L));
			assertThat(second.stateRoot(), equalTo(first.stateRoot()));
			assertThat(second.tipHash(), equalTo(first.tipHash()));
		}
	}

	/* Its authority may not have loaded yet when a node starts; the blocks it logged are certified and stand */
	@Example
	public void shouldRestoreFromTheLogWhateverTheAuthoritySaysNow() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger first = open(fileSystem);

			first.submit(mint(1, 500));
			seal(first, FOUNDATION, 2000);

			final Ledger restarted = open(fileSystem, 1000, NO_MINT);

			assertThat(restarted.height(), equalTo(1L));
			assertThat(restarted.balanceOf(account(1)), equalTo(500L));
		}
	}

	/* The proposer has put its name to one block at this height; asked again it hands that block back, and a
	   transaction that arrived in between goes into the next one */
	@Example
	public void shouldHandTheSameBlockBackWhenAskedToProposeAgainAtOneHeight() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final Optional<Block> first = ledger.propose(seed(1), 2000);

			ledger.submit(mint(2, 500));

			assertThat(ledger.propose(seed(1), 2001), equalTo(first));
		}
	}

	@Example
	public void shouldKeepOnlyWhatFitsInTheBlockItProposes() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			for (int i = 0; i < 40; i++) {
				assertThat(ledger.submit(voteAdd(ledger, 1, seed(300 + i), 0)), equalTo(Optional.empty()));
			}

			seal(ledger, FOUNDATION, 2000);

			for (int i = 40; i < 70; i++) {
				assertThat(ledger.submit(voteAdd(ledger, 1, seed(300 + i), 0)), equalTo(Optional.empty()));
			}

			final Block block = seal(ledger, FOUNDATION, 2001);

			assertThat(block.getTransactions().size(), equalTo(LedgerState.MAX_VOTES_PER_VALIDATOR - 40));
			assertThat(ledger.pending(), equalTo(0));
		}
	}

	@Example
	public void shouldShowAQuietChainIsAliveThroughHeartbeats() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			for (int validator = 1; validator <= 3; validator++) {
				assertThat(ledger.submit(Heartbeat.signed(seed(validator), chainId(), 0, ledger.tipHash(), 3000)),
					equalTo(Optional.empty()));
			}

			assertThat(ledger.height(), equalTo(0L));
			assertThat(ledger.isProgressing(3010, 60), is(true));
			assertThat(ledger.isProgressing(9000, 60), is(false));
		}
	}

	@Example
	public void shouldRefuseAHeartbeatForAnotherTip() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);
			final Heartbeat stale = Heartbeat.signed(seed(1), chainId(), 4, ledger.tipHash(), 3000);

			assertThat(ledger.submit(stale).isPresent(), is(true));
		}
	}

	@Example
	public void shouldForgetHeartbeatsWhenABlockMovesTheTip() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			for (int validator = 1; validator <= 3; validator++) {
				ledger.submit(Heartbeat.signed(seed(validator), chainId(), 0, ledger.tipHash(), 3000));
			}

			ledger.submit(mint(1, 5));
			seal(ledger, FOUNDATION, 3000);
			assertThat(ledger.isProgressing(3000, 60), is(false));
		}
	}

	/* Round of two blocks: the three foundation validators vote a gridnode in, the round runs out, and the
	   next block needs four of the four, one of which is the new one */
	@Example
	public void shouldPassARoundAndGrowTheSetByVote() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, 2, ANY_MINT);
			final List<byte[]> everyone = new ArrayList<>(FOUNDATION);
			final byte[] gridnode = seed(201);

			everyone.add(gridnode);

			for (int voter = 1; voter <= 3; voter++) {
				assertThat(ledger.submit(voteAdd(ledger, voter, gridnode, 0)), equalTo(Optional.empty()));
			}

			seal(ledger, everyone, 2000);
			ledger.submit(mint(1, 5));
			seal(ledger, everyone, 2001);

			assertThat(ledger.validators().size(), equalTo(4));
			assertThat(ledger.validators().contains(Ed25519.publicKey(gridnode)), is(true));
			assertThat(ledger.round(), equalTo(1L));

			ledger.submit(mint(2, 5));
			seal(ledger, everyone, 2002);
			assertThat(ledger.height(), equalTo(3L));
			assertThat(ledger.balanceOf(account(2)), equalTo(5L));
		}
	}
}
