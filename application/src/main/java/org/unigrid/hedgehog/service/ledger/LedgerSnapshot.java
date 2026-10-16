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

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.ledger.LegacyAddress;
import org.unigrid.hedgehog.ledger.MintSchedule;
import org.unigrid.hedgehog.ledger.Snapshot;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;

/* The bootstrap snapshot as the ledger sees it. It holds the one reader it is given for its whole life: the
   ledger replays its block log through the snapshot and must judge every claim against the same balances, so a
   snapshot file that changes under a running ledger is not followed (BootstrapSnapshot would hand out a new
   reader, this class never asks it). Whether the file is the one the genesis commits to is decided once at
   start, by SnapshotCheck. Reads are safe from any thread: the reader maps the file read-only and answers each
   lookup with absolute reads and private views, and nothing here changes after construction. */
@RequiredArgsConstructor
public final class LedgerSnapshot implements Snapshot {
	@NonNull private final SnapshotReader reader;

	@Override
	public long balanceOf(LegacyAddress address) {
		return reader.balanceUnits(address.bytes());
	}

	/* From the tip of the same reader the balances come from, so a mint is never due against another snapshot */
	public MintSchedule mintSchedule() {
		return new MintSchedule(reader.tipHeight(), reader.tipTimeMillis());
	}
}
