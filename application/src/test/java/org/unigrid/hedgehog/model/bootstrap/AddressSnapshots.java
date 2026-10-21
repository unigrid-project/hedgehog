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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

/* Snapshot files with chosen balances and a chosen tip, written by the production writer. They have to be real
   files: the reader maps its sections, which a virtual file system cannot do. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AddressSnapshots {
	public static final long MAX_UNITS = 1_000_000_000_000_000L;
	private static final int MAX_HOLDERS = 20;
	private static final int BLOCK_INTERVAL = 60;
	private static final HexFormat HEX = HexFormat.of();

	public static Arbitrary<String> hashes() {
		return Arbitraries.strings().withChars("0123456789abcdef").ofLength(Hashing.ADDRESS_HASH_SIZE * 2);
	}

	public static Arbitrary<Map<String, Long>> holdings(long maxUnits) {
		return Arbitraries.maps(hashes(), Arbitraries.longs().between(0, maxUnits)).ofMaxSize(MAX_HOLDERS);
	}

	public static byte[] bytesOf(String hash) {
		return HEX.parseHex(hash);
	}

	public static String addressOf(String hash) {
		return LegacyAddress.encode(bytesOf(hash));
	}

	/* The address hashes are lower-case hex; tipTime is the unsigned seconds of the last block, read as an int */
	@SneakyThrows
	public static Path write(Map<String, Long> holdings, int tipHeight, int tipTime) {
		final AddressRegistry addresses = new AddressRegistry();
		final long[] balances = new long[holdings.size()];
		final int[] empty = new int[tipHeight + 1];
		final int[] times = new int[tipHeight + 1];

		holdings.forEach((hash, units) -> balances[addresses.idOf(bytesOf(hash))] = units);

		for (int height = 0; height <= tipHeight; height++) {
			times[height] = tipTime - (tipHeight - height) * BLOCK_INTERVAL;
		}

		final Path path = Files.createTempFile("hhg-addresses-", ".dat");

		path.toFile().deleteOnExit();
		final Chain chain = new Chain(empty, empty, empty, times, new byte[Hashing.HASH_SIZE], tipHeight + 1);

		SnapshotWriter.write(path, chain, Ledger.builder().addresses(addresses).entries(new LedgerEntries(16))
			.transactionIds(new TransactionIdTable()).balances(balances)
			.totalUnspent(holdings.values().stream().mapToLong(Long::longValue).sum()).build()
		);
		return path;
	}

	public static Path write(Map<String, Long> holdings) {
		return write(holdings, 0, 0);
	}
}
