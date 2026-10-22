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

import java.util.List;

/* What the ledger asks of the outside, because only the node knows it: which mints are due, which it will take,
   which claims it holds back and what fee it demands. The answers are this node's policy and may differ between
   nodes, so they are asked only of what is not certified yet; a block that more than 2/3 of the validators
   signed stands whatever the answers are, which is what keeps a change of policy from forking the chain. A
   node that does not know yet answers no, which only delays a block. */
public interface LedgerAuthority {
	/* The mints this node knows to be due at this time, in a fixed order. The ledger leaves out the ones it
	   has already made. */
	List<Mint> due(long time);

	/* The entry, the amount and the moment: may this mint be made in a block of this time? The supply and what has
	   been minted to the recipient of the mint, as the mint names it, are those of the state the mint would be made
	   on, which already holds the mints before it in the same block, so a limit on either holds for the block as a
	   whole. */
	boolean authorizes(Mint mint, long time, long supply, long minted);

	/* No default for these two: an authority that forgot one would let anything through */
	boolean authorizes(Claim claim);

	/* Does this transfer pay the minimum fee this node demands? */
	boolean authorizes(Transfer transfer);

	/* The least amount a transfer to an address that holds nothing in the ledger may carry. The ledger keeps every
	   account for good and hashes each into every root, so accounts made by the million at the minimum fee would
	   slow every block. Zero takes any amount, which is what an authority that says nothing of it means. */
	default long leastNewAccountAmount() {
		return 0;
	}
}
