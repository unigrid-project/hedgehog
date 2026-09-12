/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;

/*
   Entries arrive in chain order but have to end up grouped per address. Counting how many each address
   owns and prefix-summing those counts gives every address a slot range, after which one scatter pass
   places each entry directly where it belongs. Order within an address is preserved, so a group comes
   out sorted by height without a comparison sort.
*/
@Slf4j
public final class SnapshotWriter {
	private final Chain chain;
	private final Ledger ledger;
	private final int[] order;
	private final int[] ranks;
	private final int[] starts;

	private SnapshotWriter(Chain chain, Ledger ledger) {
		this.chain = chain;
		this.ledger = ledger;
		this.order = sortedAddresses(ledger.getAddresses());
		this.ranks = ranksOf(order);
		this.starts = entryStarts();
	}

	public static void write(Path path, Chain chain, Ledger ledger) throws IOException {
		new SnapshotWriter(chain, ledger).writeTo(path);
	}

	private void writeTo(Path path) throws IOException {
		final byte[] entryTable = scatterEntries();

		@Cleanup final OutputStream stream = new BufferedOutputStream(Files.newOutputStream(path,
			StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));

		stream.write(header());
		stream.write(addressTable());
		stream.write(blockTimeTable());
		stream.write(entryTable);
		ledger.getTransactionIds().writeTo(stream);
		log.info("Wrote {} to {}", describeSize(), path);
	}

	private byte[] header() {
		final ByteBuffer buffer = ByteBuffer.allocate(SnapshotFormat.HEADER_SIZE);
		final long addressTableOffset = SnapshotFormat.HEADER_SIZE;
		final long blockTimeTableOffset = addressTableOffset + addressTableSize();
		final long entryTableOffset = blockTimeTableOffset + blockTimeTableSize();

		buffer.put(SnapshotFormat.MAGIC);
		buffer.putInt(SnapshotFormat.VERSION_OFFSET, SnapshotFormat.VERSION);
		buffer.position(SnapshotFormat.TIP_HASH_OFFSET).put(chain.getTipHash());
		buffer.putInt(SnapshotFormat.TIP_HEIGHT_OFFSET, chain.getTipHeight());
		buffer.putInt(SnapshotFormat.ADDRESS_COUNT_OFFSET, order.length);
		buffer.putLong(SnapshotFormat.ENTRY_COUNT_OFFSET, ledger.getEntries().getSize());
		buffer.putLong(SnapshotFormat.TRANSACTION_COUNT_OFFSET, ledger.getTransactionIds().getSize());
		buffer.putLong(SnapshotFormat.BUILT_AT_OFFSET, Instant.now().getEpochSecond());
		buffer.putLong(SnapshotFormat.ADDRESS_TABLE_OFFSET, addressTableOffset);
		buffer.putLong(SnapshotFormat.BLOCK_TIME_TABLE_OFFSET, blockTimeTableOffset);
		buffer.putLong(SnapshotFormat.ENTRY_TABLE_OFFSET, entryTableOffset);
		buffer.putLong(SnapshotFormat.TRANSACTION_TABLE_OFFSET, entryTableOffset + entryTableSize());
		buffer.putLong(SnapshotFormat.TOTAL_UNSPENT_OFFSET, ledger.getTotalUnspent());
		buffer.putLong(SnapshotFormat.ZEROCOIN_MINTED_OFFSET, ledger.getZerocoinMinted());
		return buffer.array();
	}

	private byte[] addressTable() {
		final ByteBuffer buffer = ByteBuffer.allocate(inMemorySize(addressTableSize(), "address table"));

		for (int rank = 0; rank < order.length; rank++) {
			final int base = rank * SnapshotFormat.ADDRESS_RECORD_SIZE;

			buffer.position(base).put(ledger.getAddresses().hashOf(order[rank]));
			buffer.putLong(base + SnapshotFormat.ADDRESS_BALANCE_OFFSET, ledger.getBalances()[order[rank]]);
			buffer.putInt(base + SnapshotFormat.ADDRESS_FIRST_ENTRY_OFFSET, starts[rank]);
			buffer.putInt(base + SnapshotFormat.ADDRESS_ENTRY_COUNT_OFFSET, starts[rank + 1] - starts[rank]);
		}

		return buffer.array();
	}

	private byte[] blockTimeTable() {
		final ByteBuffer buffer = ByteBuffer.allocate(inMemorySize(blockTimeTableSize(), "block time table"));

		for (final int time : chain.getTimes()) {
			buffer.putInt(time);
		}

		return buffer.array();
	}

	private byte[] scatterEntries() {
		final LedgerEntries entries = ledger.getEntries();
		final int[] cursor = Arrays.copyOf(starts, order.length);
		final byte[] table = new byte[inMemorySize(entryTableSize(), "entry table")];
		final ByteBuffer buffer = ByteBuffer.wrap(table);

		for (int entry = 0; entry < entries.getSize(); entry++) {
			final int rank = ranks[entries.addressAt(entry)];
			final int base = cursor[rank]++ * SnapshotFormat.ENTRY_RECORD_SIZE;

			buffer.putLong(base + SnapshotFormat.ENTRY_AMOUNT_OFFSET, entries.amountAt(entry));
			buffer.putInt(base + SnapshotFormat.ENTRY_HEIGHT_OFFSET, entries.heightAt(entry));
			buffer.putInt(base + SnapshotFormat.ENTRY_TRANSACTION_OFFSET, entries.transactionAt(entry));
			buffer.put(base + SnapshotFormat.ENTRY_KIND_OFFSET, entries.kindAt(entry));
		}

		return table;
	}

	private int[] entryStarts() {
		final LedgerEntries entries = ledger.getEntries();
		final int[] boundaries = new int[order.length + 1];

		for (int entry = 0; entry < entries.getSize(); entry++) {
			boundaries[ranks[entries.addressAt(entry)] + 1]++;
		}

		for (int rank = 0; rank < order.length; rank++) {
			boundaries[rank + 1] += boundaries[rank];
		}

		return boundaries;
	}

	private long addressTableSize() {
		return (long) order.length * SnapshotFormat.ADDRESS_RECORD_SIZE;
	}

	private long blockTimeTableSize() {
		return (long) chain.getBlockCount() * SnapshotFormat.BLOCK_TIME_RECORD_SIZE;
	}

	private long entryTableSize() {
		return (long) ledger.getEntries().getSize() * SnapshotFormat.ENTRY_RECORD_SIZE;
	}

	/*
	   Each section is assembled in one array before it is written, so a chain large enough to push a
	   section past two gigabytes has to fail loudly rather than silently truncate through an int cast.
	*/
	private static int inMemorySize(long size, String section) {
		if (size > Integer.MAX_VALUE) {
			throw new IllegalStateException("The " + section + " is " + size
				+ " bytes, which is more than this snapshot format can assemble in memory");
		}

		return (int) size;
	}

	private String describeSize() {
		return order.length + " addresses, " + ledger.getEntries().getSize() + " entries and "
			+ ledger.getTransactionIds().getSize() + " transaction ids";
	}

	private static int[] sortedAddresses(AddressRegistry addresses) {
		final Integer[] boxed = new Integer[addresses.size()];

		for (int i = 0; i < boxed.length; i++) {
			boxed[i] = i;
		}

		Arrays.sort(boxed, (left, right)
			-> Arrays.compareUnsigned(addresses.hashOf(left), addresses.hashOf(right)));

		final int[] result = new int[boxed.length];

		for (int i = 0; i < boxed.length; i++) {
			result[i] = boxed[i];
		}

		return result;
	}

	private static int[] ranksOf(int[] order) {
		final int[] ranks = new int[order.length];

		for (int rank = 0; rank < order.length; rank++) {
			ranks[order[rank]] = rank;
		}

		return ranks;
	}
}
