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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;

/* The foundation validators the chain starts with, a start time, the length of a validator round in milliseconds,
   and the snapshot of the earlier chain that claims are made against: its hash, all zeros when there is none, and
   the sum of its balances. The rounds follow each other from the start time on, the first being round 0. */
@Value
public class LedgerGenesis {
	private static final byte[] MAGIC = "UGLEDGR4".getBytes(StandardCharsets.US_ASCII);

	private final long time;
	private final long roundMillis;
	private final ValidatorSet validators;
	@Getter(AccessLevel.NONE) private final byte[] snapshotHash;
	private final long snapshotTotal;

	public LedgerGenesis(long time, long roundMillis, ValidatorSet validators, byte[] snapshotHash,
		long snapshotTotal) {
		if (roundMillis < 1) {
			throw new IllegalArgumentException("A round is at least 1 millisecond, found " + roundMillis);
		}

		if (snapshotTotal < 0) {
			throw new IllegalArgumentException("A snapshot total is 0 or more, found " + snapshotTotal);
		}

		this.time = time;
		this.roundMillis = roundMillis;
		this.validators = validators;
		this.snapshotHash = Bytes.requireSize(snapshotHash, Digests.HASH_SIZE, "A snapshot hash");
		this.snapshotTotal = snapshotTotal;
	}

	public LedgerGenesis(long time, long roundMillis, ValidatorSet validators) {
		this(time, roundMillis, validators, new byte[Digests.HASH_SIZE], 0);
	}

	public byte[] getSnapshotHash() {
		return snapshotHash.clone();
	}

	public byte[] encode() {
		final ByteBuffer out = ByteBuffer.allocate(MAGIC.length + Long.BYTES + Long.BYTES + Short.BYTES
			+ AccountKey.SIZE * validators.size() + Digests.HASH_SIZE + Long.BYTES);

		out.put(MAGIC).putLong(time).putLong(roundMillis).putShort((short) validators.size());
		validators.keys().forEach(key -> out.put(key.bytes()));
		out.put(snapshotHash).putLong(snapshotTotal);
		return out.array();
	}

	/* Hashing the whole genesis binds what is signed, such as a vote, to this chain and no other */
	public byte[] chainId() {
		return Digests.sha512(encode());
	}
}
