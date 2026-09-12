/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/*
   Millions of transaction ids are appended while the chain is replayed. Growing one flat array would
   copy hundreds of megabytes every time it doubled, so they accumulate in fixed-size chunks instead.
*/
public final class TransactionIdTable {
	private static final int IDS_PER_CHUNK = 1 << 16;
	private static final int CHUNK_SIZE = IDS_PER_CHUNK * Hashing.HASH_SIZE;

	private final List<byte[]> chunks = new ArrayList<>();
	@Getter private int size;

	public int add(byte[] identifier) {
		if (size % IDS_PER_CHUNK == 0) {
			chunks.add(new byte[CHUNK_SIZE]);
		}

		System.arraycopy(identifier, 0, chunks.get(size / IDS_PER_CHUNK),
			(size % IDS_PER_CHUNK) * Hashing.HASH_SIZE, Hashing.HASH_SIZE);
		return size++;
	}

	public void writeTo(OutputStream stream) throws IOException {
		for (int i = 0; i < chunks.size(); i++) {
			final boolean last = i == chunks.size() - 1;
			final int length = last ? (size - i * IDS_PER_CHUNK) * Hashing.HASH_SIZE : CHUNK_SIZE;

			stream.write(chunks.get(i), 0, length);
		}
	}
}
