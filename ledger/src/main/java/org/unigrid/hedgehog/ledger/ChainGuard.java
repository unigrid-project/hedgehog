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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.unigrid.hedgehog.ledger.LedgerDriver.Checkpoint;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;

/* What decides that a block, or a claim of a peer, contradicts the chain this node holds or the checkpoint it was told
   to trust. A block that does is evidence only if it is certified; one that is not is the peer's mistake and no more.
   Nothing here changes anything: the driver acts on the answers. */
final class ChainGuard {
	private ChainGuard() {
		/* Static rules only */
	}

	static String differsFromCheckpoint(long height) {
		return "Block " + height + " differs from the trusted checkpoint";
	}

	/* Why the block must not be applied, if it must not: it is not the checkpoint, or it is a second certified block at
	   a height the ledger holds, or a certified block that follows another block than the tip, which is the sign of a
	   node on another chain */
	static Optional<String> contradiction(Ledger ledger, DriverOptions options, Block block) {
		if (clashesWithCheckpoint(ledger, options, block)) {
			return Optional.of(differsFromCheckpoint(block.getHeight()));
		}

		if (forks(ledger, block)) {
			return Optional.of("Two certified blocks at height " + block.getHeight());
		}

		return diverges(ledger, block) ? Optional.of("Chain diverged at " + block.getHeight()) : Optional.empty();
	}

	/* Only the block that comes next can be certified here, and one that is not valid is left to be refused */
	private static boolean clashesWithCheckpoint(Ledger ledger, DriverOptions options, Block block) {
		return options.trusted().filter(checkpoint -> checkpoint.height() == block.getHeight()
			&& !Arrays.equals(checkpoint.hash(), block.hash())).isPresent()
			&& ledger.validateCertified(block).isEmpty();
	}

	/* A block of a height the ledger holds that is not the block it holds there */
	static boolean conflictsWithHeld(Ledger ledger, Block block) {
		return block.getHeight() <= ledger.height() && ledger.hashAt(block.getHeight())
			.filter(held -> !Arrays.equals(held, block.hash())).isPresent();
	}

	private static boolean forks(Ledger ledger, Block block) {
		return conflictsWithHeld(ledger, block) && certified(ledger, block);
	}

	/* The next block, certified by the validators there are, that does not follow the tip: this node holds a chain that
	   the validators have not built on */
	private static boolean diverges(Ledger ledger, Block block) {
		return block.getHeight() == ledger.height() + 1 && !Arrays.equals(block.getPreviousHash(), ledger.tipHash())
			&& certified(ledger, block);
	}

	/* By more than 2/3 of the validators there are now, each counted once, and every signature a precommit for the
	   block. It is the only set the ledger can show, so a fork among an earlier set goes unseen. */
	static boolean certified(Ledger ledger, Block block) {
		final ValidatorSet validators = ledger.validators();
		final byte[] chainId = ledger.chainId();
		final List<Endorsement> endorsements = block.getEndorsements();

		return endorsements.stream().map(Endorsement::signer).distinct().count() >= validators.quorum()
			&& endorsements.stream().allMatch(endorsement -> validators.contains(endorsement.signer())
				&& block.isSigned(endorsement, chainId));
	}

	/* The blocks a node starts with are held to the checkpoint as well as the ones it fetches later */
	static Optional<String> heldContradiction(Ledger ledger, DriverOptions options) {
		return options.trusted().filter(checkpoint -> contradictedByHeld(ledger, checkpoint))
			.map(checkpoint -> differsFromCheckpoint(checkpoint.height()));
	}

	private static boolean contradictedByHeld(Ledger ledger, Checkpoint checkpoint) {
		return ledger.hashAt(checkpoint.height()).filter(held -> !Arrays.equals(held, checkpoint.hash()))
			.isPresent();
	}

	/* A peer whose tip is not the block held at that height is on another fork, which only certified blocks settle */
	static boolean contradictsHeld(Ledger ledger, Status status) {
		return ledger.hashAt(status.height()).filter(held -> !Arrays.equals(held, status.tipHash())).isPresent();
	}
}
