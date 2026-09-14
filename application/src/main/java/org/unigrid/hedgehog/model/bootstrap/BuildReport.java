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

package org.unigrid.hedgehog.model.bootstrap;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Value;

@Value @Builder
public class BuildReport {
	private String tipHash;
	private int tipHeight;
	private int storedBlocks;
	private int chainBlocks;
	private int staleBlocks;
	private int addressCount;
	private long entryCount;
	private long transactionCount;
	private int unspentOutputCount;
	private BigDecimal totalUnspent;
	private BigDecimal zerocoinMinted;

	@Override
	public String toString() {
		return String.join(System.lineSeparator(),
			"Tip hash:           " + tipHash,
			"Tip height:         " + tipHeight,
			"Blocks stored:      " + storedBlocks,
			"Blocks on chain:    " + chainBlocks,
			"Stale blocks:       " + staleBlocks,
			"Addresses:          " + addressCount,
			"Ledger entries:     " + entryCount,
			"Transactions:       " + transactionCount,
			"Unspent outputs:    " + unspentOutputCount,
			"Total unspent:      " + totalUnspent,
			"Zerocoin minted:    " + zerocoinMinted);
	}
}
