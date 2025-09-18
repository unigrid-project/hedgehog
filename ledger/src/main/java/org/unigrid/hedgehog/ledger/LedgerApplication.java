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

import java.io.IOException;
import java.util.Optional;

/* What a consensus engine needs from the ledger: it takes in transactions and heartbeats, asks the proposer
   of the next height for a block, collects the other validators' signatures with sign, and commits the block
   once the certificate is whole. How the signatures travel between the validators is the engine's affair. */
public interface LedgerApplication {
	/* Empty when the transaction went into the mempool, otherwise the reason it did not */
	Optional<String> submit(Transaction transaction);

	Optional<String> submit(Heartbeat heartbeat);

	/* Empty when it is not this key's turn, or when nothing is waiting: there are no empty blocks */
	Optional<Block> propose(byte[] proposerSeed, long time);

	Block sign(Block block, byte[] validatorSeed);

	Optional<String> validate(Block block);

	/* Logs the block, then applies it; a block that is not valid is refused and leaves no trace */
	void commit(Block block) throws IOException;

	ValidatorSet validators();

	long height();

	byte[] tipHash();

	boolean isProgressing(long now, long window);
}
