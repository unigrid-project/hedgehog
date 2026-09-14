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

/*
   Answers balance and history questions straight out of the memory-mapped file. The address table is
   sorted, so a lookup is a binary search over a section small enough to stay in cache, and an address's
   entries are contiguous, so its history is a single sequential read.
*/
public final class SnapshotReader {
	private final ByteBuffer header;
	private final ByteBuffer addresses;
	private final ByteBuffer blockTimes;
	private final ByteBuffer entries;
	private final ByteBuffer transactions;
	private SignatureStatus signature;

	private SnapshotReader(FileChannel channel) throws IOException {
		this.header = map(channel, 0, SnapshotFormat.HEADER_SIZE);
		verifyFormat();

		final int addressCount = header.getInt(SnapshotFormat.ADDRESS_COUNT_OFFSET);
		final long entryCount = header.getLong(SnapshotFormat.ENTRY_COUNT_OFFSET);
		final long transactionCount = header.getLong(SnapshotFormat.TRANSACTION_COUNT_OFFSET);
		final long blockTimeCount = header.getInt(SnapshotFormat.TIP_HEIGHT_OFFSET) + 1L;

		this.addresses = map(channel, header.getLong(SnapshotFormat.ADDRESS_TABLE_OFFSET),
			addressCount * (long) SnapshotFormat.ADDRESS_RECORD_SIZE);
		this.blockTimes = map(channel, header.getLong(SnapshotFormat.BLOCK_TIME_TABLE_OFFSET),
			blockTimeCount * SnapshotFormat.BLOCK_TIME_RECORD_SIZE);
		this.entries = map(channel, header.getLong(SnapshotFormat.ENTRY_TABLE_OFFSET),
			entryCount * SnapshotFormat.ENTRY_RECORD_SIZE);
		this.transactions = map(channel, header.getLong(SnapshotFormat.TRANSACTION_TABLE_OFFSET),
			transactionCount * Hashing.HASH_SIZE);
	}

	public static SnapshotReader open(Path path) throws IOException {
		final SnapshotReader reader;

		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			reader = new SnapshotReader(channel);
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
		final byte[] tipHash = new byte[Hashing.HASH_SIZE];

		header.duplicate().position(SnapshotFormat.TIP_HASH_OFFSET).get(tipHash);

		return SnapshotInfo.builder().tipHash(BlockParser.toDisplayString(tipHash))
			.tipHeight(header.getInt(SnapshotFormat.TIP_HEIGHT_OFFSET))
			.addressCount(header.getInt(SnapshotFormat.ADDRESS_COUNT_OFFSET))
			.entryCount(header.getLong(SnapshotFormat.ENTRY_COUNT_OFFSET))
			.transactionCount(header.getLong(SnapshotFormat.TRANSACTION_COUNT_OFFSET))
			.totalUnspent(Coin.toDecimal(header.getLong(SnapshotFormat.TOTAL_UNSPENT_OFFSET)))
			.zerocoinMinted(Coin.toDecimal(header.getLong(SnapshotFormat.ZEROCOIN_MINTED_OFFSET)))
			.built(Instant.ofEpochSecond(header.getLong(SnapshotFormat.BUILT_AT_OFFSET)))
			.signature(signature).build();
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

		final int available = Math.max(0, entryCountAt(record) - offset);
		final int count = Math.min(limit, available);
		final List<AddressTransaction> result = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			result.add(transactionAt(firstEntryAt(record) + offset + i));
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

	private void verifyFormat() throws IOException {
		final byte[] magic = new byte[SnapshotFormat.MAGIC.length];

		header.duplicate().position(0).get(magic);

		if (!Arrays.equals(SnapshotFormat.MAGIC, magic)) {
			throw new IOException("Not a legacy chain snapshot");
		}

		final int version = header.getInt(SnapshotFormat.VERSION_OFFSET);

		if (version != SnapshotFormat.VERSION) {
			throw new IOException("Snapshot is format version " + version + ", this build reads "
				+ SnapshotFormat.VERSION + "; re-import the bootstrap");
		}
	}

	private static ByteBuffer map(FileChannel channel, long offset, long size) throws IOException {
		if (size > Integer.MAX_VALUE) {
			throw new IOException("Snapshot section at " + offset + " is larger than 2 GiB");
		}

		return channel.map(FileChannel.MapMode.READ_ONLY, offset, size);
	}
}
