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

package org.unigrid.hedgehog.ledger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import lombok.Value;

/* The foundation validators the chain starts with, the length of a round in blocks and a start time */
@Value
public class LedgerGenesis {
	private static final byte[] MAGIC = "UGLEDGR2".getBytes(StandardCharsets.US_ASCII);

	private final long time;
	private final int roundLength;
	private final ValidatorSet validators;

	public LedgerGenesis(long time, int roundLength, ValidatorSet validators) {
		if (roundLength < 1) {
			throw new IllegalArgumentException("A round is at least 1 block, found " + roundLength);
		}

		this.time = time;
		this.roundLength = roundLength;
		this.validators = validators;
	}

	public byte[] encode() {
		final ByteBuffer out = ByteBuffer.allocate(MAGIC.length + Long.BYTES + Integer.BYTES + Short.BYTES
			+ AccountKey.SIZE * validators.size());

		out.put(MAGIC).putLong(time).putInt(roundLength).putShort((short) validators.size());
		validators.keys().forEach(key -> out.put(key.bytes()));
		return out.array();
	}

	/* Hashing the whole genesis binds what is signed, such as a vote, to this chain and no other */
	public byte[] chainId() {
		return Digests.sha512(encode());
	}
}
