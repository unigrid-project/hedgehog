/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.proxy.chain;

import java.util.concurrent.TimeUnit;

public record ChainLimits(long capacityBytes, int retentionSeconds, int maxEntryBytes) {
	/* Entries that wait for a gap to fill may take a quarter of the capacity on top of it */
	private static final int HELD_SHARE = 4;

	public ChainLimits {
		if (maxEntryBytes < Entry.OVERHEAD) {
			throw new IllegalArgumentException("An entry needs at least " + Entry.OVERHEAD
				+ " bytes, the limit is " + maxEntryBytes);
		}

		if (capacityBytes < maxEntryBytes) {
			throw new IllegalArgumentException("A chain of " + capacityBytes + " bytes cannot hold an entry of "
				+ maxEntryBytes + " bytes");
		}

		if (retentionSeconds < 1) {
			throw new IllegalArgumentException("Entries are retained for at least a second, found "
				+ retentionSeconds);
		}
	}

	public long retentionMillis() {
		return TimeUnit.SECONDS.toMillis(retentionSeconds);
	}

	public int maxPayloadBytes() {
		return maxEntryBytes - Entry.OVERHEAD;
	}

	public long bufferBytes() {
		return Math.max(maxEntryBytes, capacityBytes / HELD_SHARE);
	}
}
