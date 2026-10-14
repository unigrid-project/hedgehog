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

import java.util.Optional;

/* The things the engine needs to know of the ledger. It never looks inside a block. */
public interface ConsensusApp {
	/* A new block for this view, with this node as its proposer and no endorsements yet; empty when there is
	   nothing to put in one */
	Optional<Block> propose(long height, int view, long time);

	/* Whether a proposed block is right for the next height, apart from its certificate */
	Optional<String> validate(Block block);

	/* Whether a block with its certificate is right, certificate included */
	Optional<String> validateCertified(Block block);

	/* Whether there is anything that needs a block, so that a quiet chain makes none */
	boolean hasWork(long time);
}
