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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

/* Snapshot bytes built field by field, for the parts of the format that can be read without mapping the file */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SyntheticSnapshots {
	@Builder(builderClassName = "HeaderBuilder")
	public static byte[] header(byte[] magic, Integer version, int tipHeight, List<Long> balances, long entryCount,
		long transactionTableOffset, long transactionCount, long totalUnspent, long zerocoinMinted) {

		final List<Long> addresses = balances == null ? List.of() : balances;
		final ByteBuffer buffer = ByteBuffer.allocate(SnapshotFormat.HEADER_SIZE
			+ addresses.size() * SnapshotFormat.ADDRESS_RECORD_SIZE).order(ByteOrder.BIG_ENDIAN);

		buffer.put(magic == null ? SnapshotFormat.MAGIC : magic)
			.putInt(SnapshotFormat.VERSION_OFFSET, version == null ? SnapshotFormat.VERSION : version)
			.putInt(SnapshotFormat.TIP_HEIGHT_OFFSET, tipHeight)
			.putInt(SnapshotFormat.ADDRESS_COUNT_OFFSET, addresses.size())
			.putLong(SnapshotFormat.ENTRY_COUNT_OFFSET, entryCount)
			.putLong(SnapshotFormat.TRANSACTION_COUNT_OFFSET, transactionCount)
			.putLong(SnapshotFormat.ADDRESS_TABLE_OFFSET, SnapshotFormat.HEADER_SIZE)
			.putLong(SnapshotFormat.TRANSACTION_TABLE_OFFSET, transactionTableOffset)
			.putLong(SnapshotFormat.TOTAL_UNSPENT_OFFSET, totalUnspent)
			.putLong(SnapshotFormat.ZEROCOIN_MINTED_OFFSET, zerocoinMinted);

		for (int i = 0; i < addresses.size(); i++) {
			buffer.putLong(SnapshotFormat.HEADER_SIZE + i * SnapshotFormat.ADDRESS_RECORD_SIZE
				+ SnapshotFormat.ADDRESS_BALANCE_OFFSET, addresses.get(i));
		}

		return buffer.array();
	}

	public static Path inMemory(byte[] contents) throws IOException {
		final Path snapshot = Jimfs.newFileSystem(Configuration.unix()).getPath("/bootstrap.dat");

		Files.write(snapshot, contents);
		return snapshot;
	}

	@SneakyThrows
	public static void assertRefused(Callable<?> call, String reason) {
		Throwable thrown = null;

		try {
			call.call();
		} catch (IOException ex) {
			thrown = ex;
		}

		assertThat(String.valueOf(thrown), containsString(reason));
	}
}
