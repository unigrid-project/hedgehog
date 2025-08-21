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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/* A validator's signed statement that it is up and sees this tip at this time. Blocks only come when they
   are needed, so a quiet chain has no new block for a long while; heartbeats are how it shows that it is
   alive and progressing all the same. They live in the mempool and never enter a block or the state. */
public record Heartbeat(AccountKey validator, long height, byte[] tipHash, long time, byte[] signature) {
	private static final byte[] TAG = "Unigrid heartbeat v1".getBytes(StandardCharsets.US_ASCII);

	public Heartbeat {
		Objects.requireNonNull(validator);

		if (height < 0) {
			throw new IllegalArgumentException("A height is 0 or more, found " + height);
		}

		tipHash = Bytes.requireSize(tipHash, Digests.HASH_SIZE, "A tip hash");
		signature = Bytes.requireSize(signature, Ed25519.SIGNATURE_SIZE, "A heartbeat signature");
	}

	public static Heartbeat signed(byte[] validatorSeed, byte[] chainId, long height, byte[] tipHash, long time) {
		final AccountKey validator = Ed25519.publicKey(validatorSeed);

		return new Heartbeat(validator, height, tipHash, time,
			Ed25519.sign(validatorSeed, payload(validator, chainId, height, tipHash, time)));
	}

	public boolean hasValidSignature(byte[] chainId) {
		return Ed25519.verify(validator, payload(validator, chainId, height, tipHash, time), signature);
	}

	@Override
	public byte[] tipHash() {
		return tipHash.clone();
	}

	@Override
	public byte[] signature() {
		return signature.clone();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Heartbeat beat && validator.equals(beat.validator) && height == beat.height
			&& Arrays.equals(tipHash, beat.tipHash) && time == beat.time
			&& Arrays.equals(signature, beat.signature);
	}

	@Override
	public int hashCode() {
		return 31 * (31 * Objects.hash(validator, height, time) + Arrays.hashCode(tipHash))
			+ Arrays.hashCode(signature);
	}

	private static byte[] payload(AccountKey validator, byte[] chainId, long height, byte[] tipHash, long time) {
		return Digests.sha512(TAG, chainId, validator.bytes(), Bytes.longBytes(height), tipHash,
			Bytes.longBytes(time));
	}
}
