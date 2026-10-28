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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/*
   Answers balance and history questions straight out of the memory-mapped file. The address table is
   sorted, so a lookup is a binary search over a section small enough to stay in cache, and an address's
   entries are contiguous, so its history is a single sequential read.
*/
public final class SnapshotReader {
	private final ByteBuffer header;
	private final SnapshotHeader fields;
	private final ByteBuffer addresses;
	private final ByteBuffer blockTimes;
	private final ByteBuffer entries;
	private final ByteBuffer transactions;
	private SignatureStatus signature;

	private SnapshotReader(FileChannel channel, Path path) throws IOException {
		this.header = map(channel, "header", 0, SnapshotFormat.HEADER_SIZE, 1);
		this.fields = SnapshotHeader.of(header, path).verifyFormat();

		final long addressCount = nonNegative("address count", header.getInt(SnapshotFormat.ADDRESS_COUNT_OFFSET));
		final long entryCount = nonNegative("entry count", header.getLong(SnapshotFormat.ENTRY_COUNT_OFFSET));
		final long transactionCount = nonNegative("transaction count",
			header.getLong(SnapshotFormat.TRANSACTION_COUNT_OFFSET));
		final long blockTimeCount = nonNegative("tip height", header.getInt(SnapshotFormat.TIP_HEIGHT_OFFSET)) + 1;

		this.addresses = map(channel, "address table", header.getLong(SnapshotFormat.ADDRESS_TABLE_OFFSET),
			addressCount, SnapshotFormat.ADDRESS_RECORD_SIZE);
		this.blockTimes = map(channel, "block time table", header.getLong(SnapshotFormat.BLOCK_TIME_TABLE_OFFSET),
			blockTimeCount, SnapshotFormat.BLOCK_TIME_RECORD_SIZE);
		this.entries = map(channel, "entry table", header.getLong(SnapshotFormat.ENTRY_TABLE_OFFSET),
			entryCount, SnapshotFormat.ENTRY_RECORD_SIZE);
		this.transactions = map(channel, "transaction table",
			header.getLong(SnapshotFormat.TRANSACTION_TABLE_OFFSET), transactionCount, Hashing.HASH_SIZE);
	}

	public static SnapshotReader open(Path path) throws IOException {
		final SnapshotReader reader;

		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			reader = new SnapshotReader(channel, path);
		}

		final SignatureStatus status = SnapshotSignature.read(path).getStatus();

		if (status == SignatureStatus.INVALID) {
			throw new IOException("The snapshot at " + path + " has a signature that does not "
				+ "verify against any trusted key and will not be used");
		}

		reader.signature = status;
		return reader;
	}

	public SnapshotInfo getInfo() {
		return fields.info().signature(signature).build();
	}

	public int tipHeight() {
		return header.getInt(SnapshotFormat.TIP_HEIGHT_OFFSET);
	}

	/* Block times are unsigned seconds, which is why the conversion goes through a long */
	public long tipTimeMillis() {
		return TimeUnit.SECONDS.toMillis(Integer.toUnsignedLong(
			blockTimes.getInt(tipHeight() * SnapshotFormat.BLOCK_TIME_RECORD_SIZE)));
	}

	/* The balance in units of 1e-8 coins, 0 for a hash that is not in the table */
	public long balanceUnits(byte[] addressHash) {
		final int record = find(addressHash);

		return record < 0 ? 0 : balanceAt(record);
	}

	public long totalBalance() {
		final int addressCount = header.getInt(SnapshotFormat.ADDRESS_COUNT_OFFSET);
		long total = 0;

		for (int record = 0; record < addressCount; record++) {
			total += balanceAt(record);
		}

		return total;
	}

	public Optional<AddressBalance> balanceOf(String address) {
		final int record = find(LegacyAddress.decode(address));

		if (record < 0) {
			return Optional.empty();
		}

		return Optional.of(AddressBalance.builder().address(address)
			.balance(Coin.toDecimal(balanceAt(record)))
			.transactionCount(entryCountAt(record)).build());
	}

	public List<AddressTransaction> transactionsOf(String address, int offset, int limit) {
		final int record = find(LegacyAddress.decode(address));

		if (record < 0) {
			return List.of();
		}

		final int clampedOffset = Math.max(0, offset);
		final int available = Math.max(0, entryCountAt(record) - clampedOffset);
		final int count = Math.max(0, Math.min(limit, available));
		final List<AddressTransaction> result = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			result.add(transactionAt(firstEntryAt(record) + clampedOffset + i));
		}

		return result;
	}

	private AddressTransaction transactionAt(int entry) {
		final int base = entry * SnapshotFormat.ENTRY_RECORD_SIZE;
		final int height = entries.getInt(base + SnapshotFormat.ENTRY_HEIGHT_OFFSET);
		final byte[] identifier = new byte[Hashing.HASH_SIZE];

		transactions.duplicate()
			.position(entries.getInt(base + SnapshotFormat.ENTRY_TRANSACTION_OFFSET) * Hashing.HASH_SIZE)
			.get(identifier);

		return AddressTransaction.builder().transaction(BlockParser.toDisplayString(identifier))
			.time(Instant.ofEpochSecond(Integer.toUnsignedLong(blockTimes.getInt(height * Integer.BYTES))))
			.height(height).amount(Coin.toDecimal(entries.getLong(base + SnapshotFormat.ENTRY_AMOUNT_OFFSET)))
			.kind(EntryKind.of(entries.get(base + SnapshotFormat.ENTRY_KIND_OFFSET))).build();
	}

	private int find(byte[] addressHash) {
		int low = 0;
		int high = header.getInt(SnapshotFormat.ADDRESS_COUNT_OFFSET) - 1;

		while (low <= high) {
			final int middle = (low + high) >>> 1;
			final int comparison = Arrays.compareUnsigned(hashAt(middle), addressHash);

			if (comparison < 0) {
				low = middle + 1;
			} else if (comparison > 0) {
				high = middle - 1;
			} else {
				return middle;
			}
		}

		return -1;
	}

	private byte[] hashAt(int record) {
		final byte[] hash = new byte[Hashing.ADDRESS_HASH_SIZE];

		addresses.duplicate().position(record * SnapshotFormat.ADDRESS_RECORD_SIZE).get(hash);
		return hash;
	}

	private long balanceAt(int record) {
		return addresses.getLong(record * SnapshotFormat.ADDRESS_RECORD_SIZE
			+ SnapshotFormat.ADDRESS_BALANCE_OFFSET);
	}

	private int firstEntryAt(int record) {
		return addresses.getInt(record * SnapshotFormat.ADDRESS_RECORD_SIZE
			+ SnapshotFormat.ADDRESS_FIRST_ENTRY_OFFSET);
	}

	private int entryCountAt(int record) {
		return addresses.getInt(record * SnapshotFormat.ADDRESS_RECORD_SIZE
			+ SnapshotFormat.ADDRESS_ENTRY_COUNT_OFFSET);
	}

	private static long nonNegative(String field, long value) throws IOException {
		if (value < 0) {
			throw new IOException("Snapshot " + field + " is negative: " + value);
		}

		return value;
	}

	/* The header is untrusted: a count or an offset that is negative, or a table that does not fit in the file,
	   is refused as a damaged file, as mapping it would fail in other ways than an I/O error */
	private static ByteBuffer map(FileChannel channel, String table, long offset, long count, int recordSize)
		throws IOException {

		nonNegative(table + " offset", offset);

		if (count > Integer.MAX_VALUE / recordSize) {
			throw new IOException("Snapshot " + table + " at " + offset + " is larger than 2 GiB");
		}

		final long size = count * recordSize;

		if (size > channel.size() - offset) {
			throw new IOException("Snapshot " + table + " at " + offset + " runs past the end of the file");
		}

		return channel.map(FileChannel.MapMode.READ_ONLY, offset, size);
	}
}
