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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HexFormat;
import java.util.Locale;
import lombok.Builder;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.ledger.Vote;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;

/* A transaction with the fields of its kind only, so a reader never mistakes the absence of a field for a zero. The
   id is the hash of the transaction as it is sent. A claim names the legacy address it moved, which is not in the
   transaction but in the key that signed it. */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LedgerTransactionView(String id, String kind, String sender, String recipient, String amount,
	String fee, Long nonce, String voter, String candidate, String action, Long round, String claimedAddress) {

	public static LedgerTransactionView of(final Transaction transaction) {
		return switch (transaction) {
			case Mint mint -> base(mint, "mint").recipient(LedgerText.text(mint.recipient()))
				.amount(LedgerText.coins(mint.amount())).build();
			case Vote vote -> base(vote, "vote").voter(vote.voter().toString())
				.candidate(vote.candidate().toString()).action(vote.action().name().toLowerCase(Locale.ROOT))
				.round(vote.round()).build();
			case Claim claim -> base(claim, "claim").recipient(claim.recipient().toString())
				.claimedAddress(LedgerText.text(claim.address())).build();
			case Transfer transfer -> base(transfer, "transfer").sender(transfer.sender().toString())
				.recipient(LedgerText.text(transfer.recipient())).amount(LedgerText.coins(transfer.amount()))
				.fee(LedgerText.coins(transfer.fee())).nonce(transfer.nonce()).build();
		};
	}

	private static LedgerTransactionViewBuilder base(final Transaction transaction, final String kind) {
		return builder().id(HexFormat.of().formatHex(transaction.id())).kind(kind);
	}
}
