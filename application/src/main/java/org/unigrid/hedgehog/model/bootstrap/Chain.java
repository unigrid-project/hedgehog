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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/*
   The active chain only, laid out by height. Everything the block files held that did not survive the
   walk back from the deepest tip is gone by the time this exists.
*/
@RequiredArgsConstructor
public final class Chain {
	private final int[] files;
	private final int[] offsets;
	private final int[] lengths;
	@Getter private final int[] times;
	@Getter private final byte[] tipHash;
	@Getter private final int storedBlockCount;

	public int getTipHeight() {
		return files.length - 1;
	}

	public int getBlockCount() {
		return files.length;
	}

	public int getStaleBlockCount() {
		return storedBlockCount - files.length;
	}

	public BlockLocation locationAt(int height) {
		return new BlockLocation(files[height], offsets[height], lengths[height]);
	}

	public int timeAt(int height) {
		return times[height];
	}
}
