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

package org.unigrid.hedgehog.service.ledger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDigest;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;

/* Whether a snapshot file is the one a genesis commits to: its content hash and the sum of its balances must both
   be the genesis's. The signature of the snapshot is not asked for, the hash in the genesis is the commitment. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SnapshotCheck {
	private static final HexFormat HEX = HexFormat.of();

	/* Empty when the snapshot matches, else what differs, with both values */
	public static Optional<String> verify(Path file, SnapshotReader reader, LedgerGenesis genesis) {
		final List<String> problems = Stream.of(hashProblem(file, genesis), totalProblem(file, reader, genesis))
			.flatMap(Optional::stream).toList();

		return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", problems));
	}

	private static Optional<String> hashProblem(Path file, LedgerGenesis genesis) {
		try {
			final byte[] actual = SnapshotDigest.of(file);

			return Arrays.equals(actual, genesis.getSnapshotHash()) ? Optional.empty()
				: Optional.of("The snapshot " + file + " has the content hash " + HEX.formatHex(actual)
					+ ", the genesis expects " + HEX.formatHex(genesis.getSnapshotHash()));
		} catch (IOException ex) {
			return Optional.of("Cannot read the snapshot " + file + ": " + ex);
		}
	}

	private static Optional<String> totalProblem(Path file, SnapshotReader reader, LedgerGenesis genesis) {
		final long actual = reader.totalBalance();

		return actual == genesis.getSnapshotTotal() ? Optional.empty()
			: Optional.of("The snapshot " + file + " adds up to " + actual + " units, the genesis expects "
				+ genesis.getSnapshotTotal());
	}
}
