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

package org.unigrid.hedgehog.s3.index;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import org.unigrid.hedgehog.s3.spi.BlobId;

/* Where a run lives and what it covers: its blob and size, the offset its index starts at, how many slots it holds
   and its first and last key */
record RunInfo(BlobId id, long size, long indexOffset, long entries, byte[] first, byte[] last) {
	boolean covers(final byte[] key) {
		return Arrays.compareUnsigned(first, key) <= 0 && Arrays.compareUnsigned(key, last) <= 0;
	}

	boolean overlaps(final byte[] from, final byte[] to) {
		return Arrays.compareUnsigned(last, from) >= 0 && Keys.before(first, to);
	}

	@Override
	public boolean equals(final Object other) {
		return other instanceof RunInfo run && id.equals(run.id) && Arrays.equals(numbers(), run.numbers())
			&& Arrays.equals(first, run.first) && Arrays.equals(last, run.last);
	}

	private long[] numbers() {
		return new long[] {size, indexOffset, entries};
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, size, indexOffset, entries, Arrays.hashCode(first), Arrays.hashCode(last));
	}

	@Override
	public String toString() {
		return "RunInfo[size=%d, indexOffset=%d, entries=%d, first=%s, last=%s]".formatted(size, indexOffset,
			entries, HexFormat.of().formatHex(first), HexFormat.of().formatHex(last));
	}
}
