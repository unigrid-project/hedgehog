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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.ledger.LedgerDriver.Checkpoint;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.ANY_MINT;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.mint;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.newFileSystem;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.nextBlock;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.state;

/* The rules that decide that a block or a claim contradicts what a ledger holds, with no driver in the way: two
   chains of one genesis that differ in what they mint, and a ledger that holds the first blocks of one of them */
public class ChainGuardTest {
	private static final List<byte[]> VALIDATORS = DriverRig.FOUR;

	private static Ledger ledgerOf(FileSystem fileSystem, String name, int blocks, long amount) throws IOException {
		final Ledger ledger = new Ledger(state(1000, ANY_MINT, VALIDATORS),
			BlockLog.open(fileSystem.getPath("/" + name + "/blocks.log")));

		for (int n = 1; n <= blocks; n++) {
			ledger.submit(mint(n, amount));
			ledger.commit(nextBlock(ledger, VALIDATORS, DriverRig.START + n));
		}

		return ledger;
	}

	private static DriverOptions trusting(Checkpoint trusted) {
		return new DriverOptions(1, 1, 1, 1, 1, 1, 1, Optional.ofNullable(trusted));
	}

	private static Block spoiled(Block block) {
		final List<Endorsement> endorsements = new ArrayList<>(block.getEndorsements());
		final byte[] signature = endorsements.get(0).signature();

		signature[10] ^= 1;
		endorsements.set(0, new Endorsement(endorsements.get(0).signer(), signature));
		return block.toBuilder().certificate(new Certificate(block.getCertificate().view(), endorsements)).build();
	}

	private static Block certifiedBy(Block block, List<Endorsement> endorsements) {
		return block.toBuilder().certificate(new Certificate(block.getCertificate().view(), endorsements)).build();
	}

	private static Block first(Ledger ledger, long height) {
		return ledger.blocksFrom(height, 1).get(0);
	}

	@Example
	public void shouldFindACertifiedBlockOfAHeldHeightWithAnotherHashAFork() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger held = ledgerOf(fileSystem, "held", 3, 10);
			final Ledger fork = ledgerOf(fileSystem, "fork", 3, 11);

			assertThat(ChainGuard.contradiction(held, trusting(null), first(fork, 2)),
				equalTo(Optional.of("Two certified blocks at height 2")));
			assertThat(ChainGuard.contradiction(held, trusting(null), first(fork, 3)),
				equalTo(Optional.of("Two certified blocks at height 3")));
		}
	}

	@Example
	public void shouldNotFindAForkInABlockThatNoQuorumHasCertified() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger held = ledgerOf(fileSystem, "held", 3, 10);
			final Block certified = first(ledgerOf(fileSystem, "fork", 3, 11), 2);
			final Endorsement one = certified.getEndorsements().get(0);
			final Block outsiders = LedgerFixtures.certify(certifiedBy(certified, List.of()), certified.getView(),
				held.chainId(), LedgerFixtures.seeds(5, 4));

			assertThat(ChainGuard.contradiction(held, trusting(null), certifiedBy(certified, List.of(one))).isPresent(),
				is(false));
			assertThat(ChainGuard.contradiction(held, trusting(null),
				certifiedBy(certified, List.of(one, one, one))).isPresent(), is(false));
			assertThat(ChainGuard.contradiction(held, trusting(null), spoiled(certified)).isPresent(), is(false));
			assertThat(ChainGuard.contradiction(held, trusting(null), outsiders).isPresent(), is(false));
		}
	}

	@Example
	public void shouldFindNothingInABlockTheLedgerHoldsOrTheNextOne() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger held = ledgerOf(fileSystem, "held", 3, 10);
			final Ledger longer = ledgerOf(fileSystem, "longer", 4, 10);

			assertThat(ChainGuard.contradiction(held, trusting(null), first(held, 2)).isPresent(), is(false));
			assertThat(ChainGuard.contradiction(held, trusting(null), first(longer, 4)).isPresent(), is(false));
		}
	}

	@Example
	public void shouldFindTheNextBlockThatIsNotTheCheckpointAndOnlyWhenItIsValid() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger held = ledgerOf(fileSystem, "held", 3, 10);
			final Ledger longer = ledgerOf(fileSystem, "longer", 4, 10);
			final Ledger fork = ledgerOf(fileSystem, "fork", 4, 11);
			final Block next = first(longer, 4);
			final Checkpoint other = new Checkpoint(4, first(fork, 4).hash());
			final Checkpoint same = new Checkpoint(4, next.hash());

			assertThat(ChainGuard.contradiction(held, trusting(other), next),
				equalTo(Optional.of("Block 4 differs from the trusted checkpoint")));
			assertThat(ChainGuard.contradiction(held, trusting(same), next).isPresent(), is(false));
			assertThat(ChainGuard.contradiction(held, trusting(other), spoiled(next)).isPresent(), is(false));
		}
	}

	@Example
	public void shouldFindNothingToHoldAgainstWithNoCheckpointAndNothingBeyondTheNextBlock() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger held = ledgerOf(fileSystem, "held", 2, 10);
			final Ledger longer = ledgerOf(fileSystem, "longer", 4, 10);
			final Checkpoint other = new Checkpoint(4, first(ledgerOf(fileSystem, "fork", 4, 11), 4).hash());

			assertThat(ChainGuard.contradiction(held, trusting(null), first(longer, 3)).isPresent(), is(false));
			assertThat(ChainGuard.contradiction(held, trusting(other), first(longer, 4)).isPresent(), is(false));
		}
	}

	@Example
	public void shouldFindACheckpointThatTheBlocksHeldContradict() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger held = ledgerOf(fileSystem, "held", 3, 10);
			final Ledger fork = ledgerOf(fileSystem, "fork", 3, 11);

			assertThat(ChainGuard.heldContradiction(held, trusting(new Checkpoint(2, first(fork, 2).hash()))),
				equalTo(Optional.of("Block 2 differs from the trusted checkpoint")));
			assertThat(ChainGuard.heldContradiction(held, trusting(new Checkpoint(3, first(fork, 3).hash()))),
				equalTo(Optional.of("Block 3 differs from the trusted checkpoint")));
		}
	}

	@Example
	public void shouldFindNoFaultWhenTheCheckpointIsMetNotReachedOrNotGiven() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger held = ledgerOf(fileSystem, "held", 3, 10);
			final Ledger fork = ledgerOf(fileSystem, "fork", 5, 11);

			assertThat(ChainGuard.heldContradiction(held, trusting(new Checkpoint(2, first(held, 2).hash())))
				.isPresent(), is(false));
			assertThat(ChainGuard.heldContradiction(held, trusting(new Checkpoint(5, first(fork, 5).hash())))
				.isPresent(), is(false));
			assertThat(ChainGuard.heldContradiction(held, trusting(null)).isPresent(), is(false));
		}
	}

	@Example
	public void shouldCallABlockCertifiedOnlyWithAQuorumOfValidatorsEachCountedOnceAndEverySignatureRight()
		throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger held = ledgerOf(fileSystem, "held", 3, 10);
			final Block block = first(held, 2);
			final List<Endorsement> all = block.getEndorsements();

			assertThat(ChainGuard.certified(held, block), is(true));
			assertThat(ChainGuard.certified(held, certifiedBy(block, all.subList(0, held.validators().quorum() - 1))),
				is(false));
			assertThat(ChainGuard.certified(held, certifiedBy(block, List.of(all.get(0), all.get(0), all.get(0)))),
				is(false));
			assertThat(ChainGuard.certified(held, spoiled(block)), is(false));
		}
	}

	@Example
	public void shouldTakeAStatusWhoseTipIsNotTheBlockHeldAtThatHeightForAClaimOnAnotherFork() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger held = ledgerOf(fileSystem, "held", 3, 10);
			final Ledger fork = ledgerOf(fileSystem, "fork", 5, 11);

			assertThat(ChainGuard.contradictsHeld(held, fork.status()), is(false));
			assertThat(ChainGuard.contradictsHeld(held, new Status(held.chainId(), 3, fork.hashAt(3).orElseThrow())),
				is(true));
			assertThat(ChainGuard.contradictsHeld(held, new Status(held.chainId(), 2, first(held, 2).hash())),
				is(false));
			assertThat(ChainGuard.contradictsHeld(held, new Status(held.chainId(), 0, held.chainId())), is(false));
		}
	}
}
