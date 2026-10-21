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
import java.util.List;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.Status;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.service.ledger.LedgerService.Report;

/* What the node says of its ledger. Amounts are coins as text with eight decimals, hashes and keys lower-case hex.
   Before there is a ledger only what the service itself knows is there; the rest is left out, not made up. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LedgerInfo(boolean running, boolean halted, String haltReason, boolean signing, String chainId,
	long height, String tipHash, String supply, Long round, List<String> validators, String minimumFee,
	long droppedInbound, List<String> unsetSporks, List<String> laggingSporks) {

	public static LedgerInfo of(final Report report) {
		return new LedgerInfo(report.running(), report.halted(), report.haltReason(), report.signing(), null,
			report.height(), report.tipHash(), null, null, null, null, report.droppedInbound(),
			names(report.unsetSporks()), names(report.laggingSporks()));
	}

	public static LedgerInfo of(final Report report, final Ledger ledger, final String minimumFee) {
		final HexFormat hex = HexFormat.of();
		final Status tip = ledger.status();

		return new LedgerInfo(report.running(), report.halted(), report.haltReason(), report.signing(),
			hex.formatHex(tip.chainId()), tip.height(), hex.formatHex(tip.tipHash()),
			LedgerText.coins(ledger.supply()), ledger.round(),
			ledger.validators().keys().stream().map(AccountKey::toString).toList(), minimumFee,
			report.droppedInbound(), names(report.unsetSporks()), names(report.laggingSporks()));
	}

	private static List<String> names(final List<Type> sporks) {
		return sporks.stream().map(Type::name).toList();
	}
}
