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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@NoArgsConstructor(access = AccessLevel.PRIVATE) @Slf4j
public final class SnapshotBuilder {
	private static final String PARTIAL_PREFIX = "snapshot-";
	private static final String PARTIAL_SUFFIX = ".part";

	public static BuildReport build(Path blocksDirectory, Path output) throws IOException {
		final BlockFileStore store = BlockFileStore.open(blocksDirectory);
		final Chain chain = ChainLinker.link(store);
		final Ledger ledger = LedgerReplay.replay(store, chain);

		verify(ledger);
		install(output, chain, ledger);
		return report(chain, ledger);
	}

	/*
	   The snapshot is written beside its destination and only moved into place once it has been read
	   back and checked, so a build that runs out of disk, is killed or fails its own check leaves what
	   was there before untouched, and a reader that has the old file mapped never sees it truncated.
	*/
	private static void install(Path output, Chain chain, Ledger ledger) throws IOException {
		final Path partial = Files.createTempFile(output.toAbsolutePath().getParent(), PARTIAL_PREFIX,
			PARTIAL_SUFFIX);

		try {
			SnapshotWriter.write(partial, chain, ledger);
			verifyWritten(partial, ledger);
			Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

		} finally {
			discard(partial);
		}
	}

	/* Cleanup must never replace the failure that caused it with one about the leftover file. */
	private static void discard(Path partial) {
		try {
			Files.deleteIfExists(partial);
		} catch (IOException ex) {
			log.atWarn().log("Could not remove the partial snapshot at {}: {}", partial, ex.getMessage());
		}
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
	   The balance held in the unspent map at the tip and the sum of every dated entry for an address both
	   come from the same credit and debit calls in the replay, so disagreement between them means one of
	   those calls recorded an entry without updating the map, or the other way around.
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
		if (summed[address] < 0) {
			throw new IllegalStateException("Address " + nameOf(ledger, address)
				+ " ends with a negative balance of " + Coin.toDecimal(summed[address]));
		}

		if (summed[address] != ledger.getBalances()[address]) {
			throw new IllegalStateException("Address " + nameOf(ledger, address) + " holds "
				+ Coin.toDecimal(ledger.getBalances()[address]) + " unspent but its entries sum to "
				+ Coin.toDecimal(summed[address]));
		}
	}

	private static String nameOf(Ledger ledger, int address) {
		return LegacyAddress.encode(ledger.getAddresses().hashOf(address));
	}

	/*
	   Reopens the file that was just written and checks it the same way a reader would, so a defect in
	   the writer itself, such as an inverted rank/address-id indirection, is caught here rather than by
	   a user relying on the wrong balance.
	*/
	private static void verifyWritten(Path output, Ledger ledger) throws IOException {
		final SnapshotInfo info = SnapshotInspector.summarise(output);
		final long writtenTotal = SnapshotInspector.totalBalance(output);

		if (info.getAddressCount() != ledger.getAddresses().size()) {
			throw new IllegalStateException("Snapshot has " + info.getAddressCount()
				+ " addresses, the ledger has " + ledger.getAddresses().size());
		}

		if (info.getEntryCount() != ledger.getEntries().getSize()) {
			throw new IllegalStateException("Snapshot has " + info.getEntryCount()
				+ " entries, the ledger has " + ledger.getEntries().getSize());
		}

		if (writtenTotal != ledger.getTotalUnspent()) {
			throw new IllegalStateException("Snapshot address table sums to " + Coin.toDecimal(writtenTotal)
				+ ", the ledger total unspent is " + Coin.toDecimal(ledger.getTotalUnspent()));
		}
	}
}
