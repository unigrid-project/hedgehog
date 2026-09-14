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
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Cleanup;
import lombok.NoArgsConstructor;

/*
   Checks a snapshot without memory-mapping it. SnapshotReader maps five regions and there is no
   portable way to release a mapping on demand, so a file it has opened cannot be renamed or deleted
   on Windows until a garbage collection happens to unmap it. Every caller that validates a file it
   is about to move, truncate or append to therefore comes here instead: the download before it
   installs, the builder before it reports, and the sign command before it rewrites the trailing
   block.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SnapshotInspector {
	/*
	   The format is checked first, so a file that is not a snapshot fails on that rather than on a
	   content length derived from a header nobody has validated. The signature state is returned
	   rather than thrown on, because only the caller knows what it was about to do with the file and
	   can say so: the temporary name this sees is not a name the user would recognise.
	*/
	public static SignatureStatus validate(Path snapshot) throws IOException {
		verifyFormat(readHeader(snapshot), snapshot);
		return SnapshotSignature.read(snapshot).getStatus();
	}

	public static SnapshotInfo summarise(Path snapshot) throws IOException {
		final ByteBuffer header = readHeader(snapshot);

		verifyFormat(header, snapshot);

		return SnapshotInfo.builder()
			.tipHeight(header.getInt(SnapshotFormat.TIP_HEIGHT_OFFSET))
			.addressCount(header.getInt(SnapshotFormat.ADDRESS_COUNT_OFFSET))
			.entryCount(header.getLong(SnapshotFormat.ENTRY_COUNT_OFFSET))
			.transactionCount(header.getLong(SnapshotFormat.TRANSACTION_COUNT_OFFSET))
			.totalUnspent(Coin.toDecimal(header.getLong(SnapshotFormat.TOTAL_UNSPENT_OFFSET)))
			.zerocoinMinted(Coin.toDecimal(header.getLong(SnapshotFormat.ZEROCOIN_MINTED_OFFSET)))
			.build();
	}

	public static long totalBalance(Path snapshot) throws IOException {
		@Cleanup final FileChannel channel = FileChannel.open(snapshot, StandardOpenOption.READ);
		final ByteBuffer header = readHeader(snapshot);
		final ByteBuffer record = ByteBuffer.allocate(SnapshotFormat.ADDRESS_RECORD_SIZE)
			.order(ByteOrder.BIG_ENDIAN);
		long total = 0;

		channel.position(header.getLong(SnapshotFormat.ADDRESS_TABLE_OFFSET));

		for (int address = header.getInt(SnapshotFormat.ADDRESS_COUNT_OFFSET); address > 0; address--) {
			record.clear();

			if (channel.read(record) != SnapshotFormat.ADDRESS_RECORD_SIZE) {
				throw new IOException("Snapshot ends inside its address table");
			}

			total += record.getLong(SnapshotFormat.ADDRESS_BALANCE_OFFSET);
		}

		return total;
	}

	static void verifyFormat(ByteBuffer header, Path snapshot) throws IOException {
		final byte[] magic = new byte[SnapshotFormat.MAGIC.length];

		header.duplicate().position(0).get(magic);

		if (!Arrays.equals(SnapshotFormat.MAGIC, magic)) {
			throw new IOException(snapshot + " is not a legacy chain snapshot");
		}

		final int version = header.getInt(SnapshotFormat.VERSION_OFFSET);

		if (version != SnapshotFormat.VERSION) {
			throw new IOException("Snapshot is format version " + version + ", this build reads "
				+ SnapshotFormat.VERSION + "; re-import the bootstrap");
		}
	}

	private static ByteBuffer readHeader(Path snapshot) throws IOException {
		@Cleanup final FileChannel channel = FileChannel.open(snapshot, StandardOpenOption.READ);
		final ByteBuffer header = ByteBuffer.allocate(SnapshotFormat.HEADER_SIZE)
			.order(ByteOrder.BIG_ENDIAN);

		if (channel.read(header) != SnapshotFormat.HEADER_SIZE) {
			throw new IOException(snapshot + " is too short to hold a snapshot header");
		}

		return header.flip();
	}
}
