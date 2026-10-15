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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);

	private static Ledger open(FileSystem fileSystem, LedgerState state) throws IOException {
		return new Ledger(state, BlockLog.open(fileSystem.getPath("/data/blocks.log")));
	}

	private static Ledger open(FileSystem fileSystem, int roundLength, LedgerAuthority authority) throws IOException {
		return open(fileSystem, state(roundLength, authority, FOUNDATION));
	}

	private static Ledger open(FileSystem fileSystem) throws IOException {
		return open(fileSystem, 1000, ANY_MINT);
	}

	private static byte[] chainId() {
		return state(1000, ANY_MINT, FOUNDATION).chainId();
	}

	/* What the engine will do over the network, done in one place: whoever is due proposes, every validator of the
	   set among these signs, and the block is committed */
	private static Block seal(Ledger ledger, List<byte[]> everyone, long time) throws IOException {
		final Block block = nextBlock(ledger, everyone, ledger.validators().size(), time);

		ledger.commit(block);
		return block;
	}

	private static Transfer send(long amount, long nonce) {
		return Transfer.signed(accountSeed(1), account(9), amount, 1, nonce, chainId());
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

			for (final AccountKey validator : ledger.validators().keys()) {
				assertThat(ledger.propose(1, 0, validator, 2000), equalTo(Optional.empty()));
			}
		}
	}

	@Example
	public void shouldLetOnlyTheProposerOfTheHeightAndViewPropose() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			assertThat(ledger.propose(1, 0, key(2), 2000), equalTo(Optional.empty()));
			assertThat(ledger.propose(1, 0, key(3), 2000), equalTo(Optional.empty()));
			assertThat(ledger.propose(1, 0, key(99), 2000), equalTo(Optional.empty()));
			assertThat(ledger.propose(1, 0, key(1), 2000).isPresent(), is(true));
		}
	}

	@Example
	public void shouldProposeOnlyForTheNextHeight() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			assertThat(ledger.propose(2, 0, key(2), 2000), equalTo(Optional.empty()));
			assertThat(ledger.propose(1, 0, key(1), 2000).isPresent(), is(true));

			seal(ledger, FOUNDATION, 2000);
			ledger.submit(mint(2, 500));

			assertThat(ledger.propose(1, 0, key(1), 2001), equalTo(Optional.empty()));
			assertThat(ledger.propose(3, 0, key(3), 2001), equalTo(Optional.empty()));
			assertThat(ledger.propose(2, 0, key(2), 2001).isPresent(), is(true));
		}
	}

	@Example
	public void shouldProposeAnUnendorsedBlockForItsProposer() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final Block proposed = ledger.propose(1, 0, key(1), 2000).get();

			assertThat(proposed.getEndorsements().isEmpty(), is(true));
			assertThat(proposed.getProposer(), equalTo(key(1)));
			assertThat(proposed.getView(), equalTo(0));
		}
	}

	@Example
	public void shouldProposeInTheViewItIsAskedFor() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);
			final AccountKey proposer = ledger.validators().proposerAt(1, 2);

			ledger.submit(mint(1, 500));

			assertThat(ledger.propose(1, 0, proposer, 2000), equalTo(Optional.empty()));

			final Block proposed = ledger.propose(1, 2, proposer, 2000).get();

			assertThat(proposed.getView(), equalTo(2));
			assertThat(proposed.getProposer(), equalTo(proposer));
			assertThat(ledger.validate(proposed), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldNotCommitABlockWithoutAQuorumAndLogNothing() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final Block proposed = ledger.propose(1, 0, key(1), 2000).get();

			assertThrows(InvalidBlockException.class, () -> ledger.commit(proposed));
			assertThat(ledger.height(), equalTo(0L));
			assertThat(open(fileSystem).height(), equalTo(0L));
		}
	}

	/* A vote is a promise that the block is right, so a validator votes for nothing it has not checked */
	@Example
	public void shouldRefuseToVoteForABlockThatIsNotValid() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final Block proposed = ledger.propose(1, 0, key(1), 2000).get();
			final Block wrongRoot = proposed.toBuilder().stateRoot(new byte[Digests.HASH_SIZE]).build();
			final Block wrongTransactions = proposed.toBuilder().transactions(List.of(mint(1, 999))).build();

			assertThat(ledger.validate(wrongRoot).orElse(""), containsString("State root does not match"));
			assertThat(ledger.validate(wrongTransactions).orElse(""), containsString("Transaction root"));
			assertThat(ledger.validate(proposed), equalTo(Optional.empty()));
		}
	}

	/* The check before a vote is made on a block with no signatures yet, the check after is made on the
	   certificate too, which is where the view of the certificate is looked at */
	@Example
	public void shouldValidateAProposalApartFromItsCertificate() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final AccountKey proposer = ledger.validators().proposerAt(1, 2);
			final Block proposed = ledger.propose(1, 2, proposer, 2000).get();
			final Block earlierView = certify(proposed, 1, ledger.chainId(), FOUNDATION);
			final Block thin = certify(proposed, 2, ledger.chainId(), FOUNDATION.subList(0, 1));
			final Block whole = certify(proposed, 2, ledger.chainId(), FOUNDATION);

			assertThat(ledger.validate(proposed), equalTo(Optional.empty()));
			assertThat(ledger.validateCertified(proposed).isPresent(), is(true));
			assertThat(ledger.validateCertified(earlierView).orElse(""), containsString("earlier view"));
			assertThat(ledger.validateCertified(thin).orElse(""), containsString("quorum"));
			assertThat(ledger.validateCertified(whole), equalTo(Optional.empty()));
		}
	}

	/* The answer is given for a block that no one has certified, so asking leaves no trace: not in the state and
	   not in the mempool, and the next proposal is the same */
	@Example
	public void shouldChangeNothingWhenAskedToValidate() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final Block proposed = ledger.propose(1, 0, key(1), 2000).get();
			final Block wrongRoot = proposed.toBuilder().stateRoot(new byte[Digests.HASH_SIZE]).build();
			final byte[] root = ledger.stateRoot();

			assertThat(ledger.validate(proposed), equalTo(Optional.empty()));
			assertThat(ledger.validate(wrongRoot).isPresent(), is(true));

			assertThat(ledger.height(), equalTo(0L));
			assertThat(ledger.stateRoot(), equalTo(root));
			assertThat(ledger.pending(), equalTo(1));
			assertThat(ledger.propose(1, 0, key(1), 2000), equalTo(Optional.of(proposed)));
		}
	}

	@Example
	public void shouldNotProposeInThePast() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));
			assertThat(ledger.propose(1, 0, key(1), 5).get().getTime(), equalTo(1000L));
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
	public void shouldSayThatAWaitingTransactionThatIsNoTransferIsWaiting() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			assertThat(ledger.submit(mint(1, 500)), equalTo(Optional.of("Already waiting for a block")));
		}
	}

	@Example
	public void shouldGiveATransferThatCannotBeQueuedItsOwnReason() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));
			seal(ledger, FOUNDATION, 2000);

			assertThat(ledger.submit(send(1, 0)), equalTo(Optional.empty()));
			assertThat(ledger.submit(send(1, 0)).get(), containsString("Not queued"));
			assertThat(ledger.submit(send(1, Mempool.NONCE_WINDOW)).get(), containsString("Not queued"));
			assertThat(ledger.pending(), equalTo(1));
		}
	}

	@Example
	public void shouldNameAUsedNonceWhenATransferIsSubmittedForIt() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));
			seal(ledger, FOUNDATION, 2000);
			ledger.submit(send(1, 0));
			seal(ledger, FOUNDATION, 2001);

			assertThat(ledger.submit(send(2, 0)).get(), containsString("already used"));
		}
	}

	@Example
	public void shouldMoveFundsAndPayTheFeesThroughTheLedger() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 1000));
			seal(ledger, FOUNDATION, 2000);

			assertThat(ledger.submit(paying(ledger, 300, 9, 0)), equalTo(Optional.empty()));
			seal(ledger, FOUNDATION, 3000);

			assertThat(ledger.balanceOf(account(2)), equalTo(300L));
			assertThat(ledger.balanceOf(account(1)), equalTo(691L));

			seal(ledger, FOUNDATION, 4000);

			for (int validator = 1; validator <= 3; validator++) {
				assertThat(ledger.balanceOf(key(validator)), equalTo(3L));
			}

			assertThat(ledger.supply(), equalTo(1000L));
			assertThat(proposal(ledger, 5000), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldTakeTransfersQueuedOutOfOrderIntoOneBlockInNonceOrder() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 1000));
			seal(ledger, FOUNDATION, 2000);

			assertThat(ledger.submit(paying(ledger, 20, 1, 1)), equalTo(Optional.empty()));
			assertThat(ledger.submit(paying(ledger, 10, 1, 0)), equalTo(Optional.empty()));
			assertThat(ledger.submit(paying(ledger, 5, 1, Mempool.NONCE_WINDOW)).isPresent(), is(true));

			final Block block = seal(ledger, FOUNDATION, 3000);

			assertThat(block.getTransactions().stream().map(transaction -> ((Transfer) transaction).nonce()).toList(),
				equalTo(List.of(0L, 1L)));
			assertThat(ledger.balanceOf(account(2)), equalTo(30L));
		}
	}

	@Example
	public void shouldRefuseToReplayATransferAfterARestart() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger first = open(fileSystem);

			first.submit(mint(1, 1000));
			seal(first, FOUNDATION, 2000);

			final Transfer paid = paying(first, 300, 9, 0);

			first.submit(paid);
			seal(first, FOUNDATION, 3000);
			seal(first, FOUNDATION, 4000);

			final Ledger second = open(fileSystem);

			assertThat(second.balanceOf(account(2)), equalTo(300L));
			assertThat(second.supply(), equalTo(first.supply()));
			assertThat(second.stateRoot(), equalTo(first.stateRoot()));
			assertThat(second.submit(paid).orElse(""), containsString("already used"));
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

	@Example
	public void shouldRestoreALegacyMintFromTheLog() throws IOException {
		final LegacyAddress legacy = new LegacyAddress(new byte[LegacyAddress.SIZE]);

		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger first = open(fileSystem);

			first.submit(new Mint(legacy, 500, reference(1)));
			seal(first, FOUNDATION, 2000);

			final Ledger second = open(fileSystem);

			assertThat(second.balanceOf(legacy), equalTo(500L));
			assertThat(second.supply(), equalTo(500L));
			assertThat(second.stateRoot(), equalTo(first.stateRoot()));
		}
	}

	@Example
	public void shouldRestoreAClaimAndTheMintsAroundItFromTheLog() throws IOException {
		final LegacyWallet wallet = new LegacyWallet(1, true);
		final Map<LegacyAddress, Long> balances = Map.of(wallet.address(), 500L);

		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger first = open(fileSystem, stateWithSnapshot(ANY_MINT, FOUNDATION, 500, balances));

			first.submit(new Mint(wallet.address(), 30, reference(1)));
			seal(first, FOUNDATION, 2000);
			assertThat(first.submit(wallet.claim(account(1), first.chainId())), equalTo(Optional.empty()));
			seal(first, FOUNDATION, 3000);
			first.submit(new Mint(wallet.address(), 20, reference(2)));
			seal(first, FOUNDATION, 4000);

			final Ledger second = open(fileSystem, stateWithSnapshot(NO_MINT, FOUNDATION, 500, balances));

			assertThat(second.balanceOf(account(1)), equalTo(550L));
			assertThat(second.balanceOf(wallet.address()), equalTo(0L));
			assertThat(second.supply(), equalTo(550L));
			assertThat(second.stateRoot(), equalTo(first.stateRoot()));
		}
	}

	@Example
	public void shouldRefuseToClaimTheSameAddressAgainThroughTheLedger() throws IOException {
		final LegacyWallet wallet = new LegacyWallet(1, true);
		final Map<LegacyAddress, Long> balances = Map.of(wallet.address(), 200L,
			new LegacyWallet(2, true).address(), 300L);

		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, stateWithSnapshot(ANY_MINT, FOUNDATION, 500, balances));

			ledger.submit(wallet.claim(account(1), ledger.chainId()));
			seal(ledger, FOUNDATION, 2000);

			final Optional<String> refused = ledger.submit(wallet.claim(account(2), ledger.chainId()));

			assertThat(refused.orElse(""), containsString("already claimed"));
			assertThat(ledger.balanceOf(account(2)), equalTo(0L));
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

	/* The ledger remembers no proposal: the engine keeps the block it put forward and the sign state keeps what it
	   signed, so asking again builds a new block from what waits now, and the mempool is left as it was */
	@Example
	public void shouldBuildEachProposalFromWhatWaitsWhenAsked() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem);

			ledger.submit(mint(1, 500));

			final Block first = ledger.propose(1, 0, key(1), 2000).get();

			ledger.submit(mint(2, 500));

			final Block second = ledger.propose(1, 0, key(1), 2001).get();

			assertThat(first.getTransactions(), equalTo(List.of(mint(1, 500))));
			assertThat(second.getTransactions(), equalTo(List.of(mint(1, 500), mint(2, 500))));
			assertThat(ledger.pending(), equalTo(2));
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
