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
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.LegacyAddress;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;

/* An address of either kind as the ledger holds it. Only an account has a nonce, the number of transfers it has made;
   only a legacy address has something to claim, and an account that has claimed it. Of a legacy address, `balance` is
   what mints have put on it and `claimable` is that and what the snapshot holds for it, all of which one claim moves. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LedgerAccount(String address, String kind, String balance, Long nonce, String mintedBySporks,
	String claimable, String claimedBy) {

	public static LedgerAccount of(final Ledger ledger, final Address address) {
		return switch (address) {
			case AccountKey key -> new LedgerAccount(key.toString(), "account", coins(ledger.balanceOf(key)),
				ledger.nonceOf(key), coins(ledger.mintedTo(key)), null, null);
			case LegacyAddress legacy -> new LedgerAccount(LedgerText.text(legacy), "legacy",
				coins(ledger.balanceOf(legacy)), null, coins(ledger.mintedTo(legacy)),
				coins(ledger.claimable(legacy)),
				ledger.claimedBy(legacy).map(AccountKey::toString).orElse(null));
		};
	}

	private static String coins(final long units) {
		return LedgerText.coins(units);
	}
}
