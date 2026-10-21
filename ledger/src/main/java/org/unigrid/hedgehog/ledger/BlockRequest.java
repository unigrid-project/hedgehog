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

/* A node asks a peer for up to `max` committed blocks, in order, starting at height `from`. The most it can ask for
   is what one batch holds, so a peer never has to read more than that for one request. */
public record BlockRequest(long from, int max) {
	public BlockRequest {
		if (from < 1) {
			throw new IllegalArgumentException("Heights start at 1, found " + from);
		}

		if (max < 1 || max > BlockBatch.MAX_BLOCKS) {
			throw new IllegalArgumentException("A request is for 1 to " + BlockBatch.MAX_BLOCKS
				+ " blocks, found " + max);
		}
	}
}
