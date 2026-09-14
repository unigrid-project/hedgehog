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

import java.io.IOException;
import java.nio.file.Path;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@NoArgsConstructor(access = AccessLevel.PRIVATE) @Slf4j
public final class SnapshotBuilder {
	public static BuildReport build(Path blocksDirectory, Path output) throws IOException {
		final BlockFileStore store = BlockFileStore.open(blocksDirectory);
		final Chain chain = ChainLinker.link(store);
		final Ledger ledger = LedgerReplay.replay(store, chain);

		verify(ledger);
		SnapshotWriter.write(output, chain, ledger);
		return report(chain, ledger);
	}

	private static BuildReport report(Chain chain, Ledger ledger) {
		return BuildReport.builder().tipHash(BlockParser.toDisplayString(chain.getTipHash()))
			.tipHeight(chain.getTipHeight()).storedBlocks(chain.getStoredBlockCount())
			.chainBlocks(chain.getBlockCount()).staleBlocks(chain.getStaleBlockCount())
			.addressCount(ledger.getAddresses().size()).entryCount(ledger.getEntries().getSize())
			.transactionCount(ledger.getTransactionIds().getSize())
			.unspentOutputCount(ledger.getUnspentOutputCount())
			.totalUnspent(Coin.toDecimal(ledger.getTotalUnspent()))
			.zerocoinMinted(Coin.toDecimal(ledger.getZerocoinMinted())).build();
	}

	/*
	   The balance held in the unspent map at the tip and the sum of every dated entry for an address are
	   arrived at by completely different routes, so disagreement between them means the replay is wrong.
	*/
	private static void verify(Ledger ledger) {
		final LedgerEntries entries = ledger.getEntries();
		final long[] summed = new long[ledger.getAddresses().size()];

		for (int entry = 0; entry < entries.getSize(); entry++) {
			summed[entries.addressAt(entry)] += entries.amountAt(entry);
		}

		for (int address = 0; address < summed.length; address++) {
			verifyAddress(ledger, summed, address);
		}

		log.info("Verified {} address balances against {} ledger entries", summed.length, entries.getSize());
	}

	private static void verifyAddress(Ledger ledger, long[] summed, int address) {
		final String name = LegacyAddress.encode(ledger.getAddresses().hashOf(address));

		if (summed[address] < 0) {
			throw new IllegalStateException("Address " + name + " ends with a negative balance of "
				+ Coin.toDecimal(summed[address]));
		}

		if (summed[address] != ledger.getBalances()[address]) {
			throw new IllegalStateException("Address " + name + " holds "
				+ Coin.toDecimal(ledger.getBalances()[address]) + " unspent but its entries sum to "
				+ Coin.toDecimal(summed[address]));
		}
	}
}
