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
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

/* The fields at the start of a snapshot (SnapshotFormat), read and checked in one place for every class that opens
   one. The file is named in what is refused, as the caller may hand in a temporary name. */
final class SnapshotHeader {
	private final ByteBuffer buffer;
	private final Path snapshot;

	private SnapshotHeader(ByteBuffer buffer, Path snapshot) {
		this.buffer = buffer;
		this.snapshot = snapshot;
	}

	/* From the start of the channel, whatever its position */
	static SnapshotHeader read(FileChannel channel, Path snapshot) throws IOException {
		final ByteBuffer buffer = ByteBuffer.allocate(SnapshotFormat.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);

		channel.position(0);

		if (channel.read(buffer) != SnapshotFormat.HEADER_SIZE) {
			throw new IOException(snapshot + " is too short to hold a snapshot header");
		}

		return new SnapshotHeader(buffer.flip(), snapshot);
	}

	/* Of a header that the caller holds already, such as one it mapped */
	static SnapshotHeader of(ByteBuffer buffer, Path snapshot) {
		return new SnapshotHeader(buffer, snapshot);
	}

	SnapshotHeader verifyFormat() throws IOException {
		final byte[] magic = new byte[SnapshotFormat.MAGIC.length];

		buffer.duplicate().position(0).get(magic);

		if (!Arrays.equals(SnapshotFormat.MAGIC, magic)) {
			throw new IOException(snapshot + " is not a legacy chain snapshot");
		}

		final int version = buffer.getInt(SnapshotFormat.VERSION_OFFSET);

		if (version != SnapshotFormat.VERSION) {
			throw new IOException("Snapshot is format version " + version + ", this build reads "
				+ SnapshotFormat.VERSION + "; re-import the bootstrap");
		}

		return this;
	}

	/* What the header says of the snapshot; whether it is signed is told by its trailing block, not by this */
	SnapshotInfo.SnapshotInfoBuilder info() {
		final byte[] tipHash = new byte[Hashing.HASH_SIZE];

		buffer.duplicate().position(SnapshotFormat.TIP_HASH_OFFSET).get(tipHash);

		return SnapshotInfo.builder().tipHash(BlockParser.toDisplayString(tipHash))
			.tipHeight(buffer.getInt(SnapshotFormat.TIP_HEIGHT_OFFSET))
			.addressCount(buffer.getInt(SnapshotFormat.ADDRESS_COUNT_OFFSET))
			.entryCount(buffer.getLong(SnapshotFormat.ENTRY_COUNT_OFFSET))
			.transactionCount(buffer.getLong(SnapshotFormat.TRANSACTION_COUNT_OFFSET))
			.totalUnspent(Coin.toDecimal(buffer.getLong(SnapshotFormat.TOTAL_UNSPENT_OFFSET)))
			.zerocoinMinted(Coin.toDecimal(buffer.getLong(SnapshotFormat.ZEROCOIN_MINTED_OFFSET)))
			.built(Instant.ofEpochSecond(buffer.getLong(SnapshotFormat.BUILT_AT_OFFSET)));
	}

	int addressCount() {
		return buffer.getInt(SnapshotFormat.ADDRESS_COUNT_OFFSET);
	}

	long addressTableOffset() {
		return buffer.getLong(SnapshotFormat.ADDRESS_TABLE_OFFSET);
	}

	/* Up to the signature, which is appended after the transaction ids */
	long contentLength() {
		return buffer.getLong(SnapshotFormat.TRANSACTION_TABLE_OFFSET)
			+ buffer.getLong(SnapshotFormat.TRANSACTION_COUNT_OFFSET) * Hashing.HASH_SIZE;
	}
}
