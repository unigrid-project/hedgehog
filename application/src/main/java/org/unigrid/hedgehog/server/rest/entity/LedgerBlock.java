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

package org.unigrid.hedgehog.server.rest.entity;

import java.util.HexFormat;
import java.util.List;
import org.unigrid.hedgehog.ledger.Block;

/* A block as committed: its header, its transactions and the validators whose precommits make its certificate. The
   time is in Unix milliseconds. */
public record LedgerBlock(long height, long view, long time, String hash, String previousHash, String stateRoot,
	String transactionRoot, String proposer, List<LedgerTransactionView> transactions, List<String> certifiedBy,
	int endorsements) {

	public static LedgerBlock of(final Block block) {
		final HexFormat hex = HexFormat.of();
		final List<String> signers = block.getEndorsements().stream()
			.map(endorsement -> endorsement.signer().toString()).toList();

		return new LedgerBlock(block.getHeight(), block.getView(), block.getTime(), hex.formatHex(block.hash()),
			hex.formatHex(block.getPreviousHash()), hex.formatHex(block.getStateRoot()),
			hex.formatHex(block.getTransactionRoot()), block.getProposer().toString(),
			block.getTransactions().stream().map(LedgerTransactionView::of).toList(), signers, signers.size());
	}
}
