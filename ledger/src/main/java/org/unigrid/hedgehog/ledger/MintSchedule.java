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

package org.unigrid.hedgehog.ledger;

/* When a mint of the spork becomes due. The chain before this one counted blocks and the entries of the mint
   spork sit at heights of that chain. This chain has no such heights, so a height is turned into a time in Unix
   milliseconds: the time of the last block of the snapshot plus one block interval of the old chain for every
   height after it.
   Entries at or below the snapshot tip are already in the snapshot balances and never mint here. */
public record MintSchedule(long tipHeight, long tipTime) {
	public static final long INTERVAL = 60_000;

	public MintSchedule {
		if (tipHeight < 0 || tipTime < 0) {
			throw new IllegalArgumentException("A height and a time are 0 or more, found " + tipHeight + " and "
				+ tipTime);
		}
	}

	public boolean mintsOnLedger(long height) {
		return height > tipHeight;
	}

	public long dueTime(long height) {
		if (!mintsOnLedger(height)) {
			throw new IllegalArgumentException("Height " + height + " is not above the snapshot tip "
				+ tipHeight);
		}

		return Math.addExact(tipTime, Math.multiplyExact(height - tipHeight, INTERVAL));
	}
}
