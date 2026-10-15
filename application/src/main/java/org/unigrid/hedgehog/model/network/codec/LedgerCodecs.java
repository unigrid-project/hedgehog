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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.ledger.Vote;

public final class LedgerCodecs {
	public static final int MAX_TRANSACTION_SIZE = Math.max(Math.max(Mint.MAX_ENCODED_SIZE, Vote.ENCODED_SIZE),
		Math.max(Claim.MAX_ENCODED_SIZE, Transfer.MAX_ENCODED_SIZE));

	/* The byte bound of a batch counts the blocks only, not its block count and the length of each block */
	public static final int MAX_BATCH_SIZE = BlockBatch.MAX_BYTES + 64 * 1024;

	private LedgerCodecs() {
		/* Static helpers only */
	}

	public static void writeBytes(final ByteBuf out, final byte[] bytes) {
		out.writeInt(bytes.length);
		out.writeBytes(bytes);
	}

	public static byte[] readBytes(final ByteBuf in, final int max) {
		final int length = in.readInt();

		if (length < 0 || length > max) {
			throw new IllegalArgumentException("Invalid byte array length " + length + ", at most " + max);
		}

		return readFixed(in, length);
	}

	/* Slicing first makes a forged length fail on the bytes actually present, before anything is allocated */
	public static byte[] readFixed(final ByteBuf in, final int size) {
		return ByteBufUtil.getBytes(in.readSlice(size));
	}
}
