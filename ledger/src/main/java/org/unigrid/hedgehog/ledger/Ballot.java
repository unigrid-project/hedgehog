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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/* A validator's prevote or precommit in one view of one height, for a block by its hash or for no block at all
   (the zero hash). The signature of a precommit is exactly the one an endorsement carries, so more than 2/3 of
   the precommits for a block are its certificate. A prevote is signed over its own tag, so it can never be
   taken for a precommit. */
public record Ballot(Type type, long height, int view, byte[] blockHash, AccountKey validator, byte[] signature)
	implements Message {
	static final byte[] NIL = new byte[Digests.HASH_SIZE];
	private static final byte[] PREVOTE_TAG = "Unigrid prevote v1".getBytes(StandardCharsets.US_ASCII);

	public enum Type {
		PREVOTE, PRECOMMIT
	}

	public Ballot {
		Objects.requireNonNull(type);
		Objects.requireNonNull(validator);

		if (height < 1 || view < 0) {
			throw new IllegalArgumentException("A height is 1 or more and a view 0 or more, found " + height
				+ " and " + view);
		}

		blockHash = Bytes.requireSize(blockHash, Digests.HASH_SIZE, "A ballot block hash");
		signature = Bytes.requireSize(signature, Ed25519.SIGNATURE_SIZE, "A ballot signature");
	}

	/* A null hash is a ballot for no block */
	public static Ballot signed(Type type, byte[] seed, byte[] chainId, long height, int view, byte[] blockHash) {
		final byte[] hash = blockHash == null ? NIL : blockHash;
		final byte[] signature = type == Type.PREVOTE
			? Ed25519.sign(seed, prevotePayload(chainId, height, view, hash))
			: Precommit.sign(seed, chainId, height, view, hash);

		return new Ballot(type, height, view, hash, Ed25519.publicKey(seed), signature);
	}

	public boolean hasValidSignature(byte[] chainId) {
		return type == Type.PREVOTE
			? Ed25519.verify(validator, prevotePayload(chainId, height, view, blockHash), signature)
			: Precommit.isSigned(endorsement(), chainId, height, view, blockHash);
	}

	public boolean isNil() {
		return Arrays.equals(blockHash, NIL);
	}

	public Endorsement endorsement() {
		return new Endorsement(validator, signature);
	}

	@Override
	public byte[] blockHash() {
		return blockHash.clone();
	}

	@Override
	public byte[] signature() {
		return signature.clone();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Ballot ballot && sameVote(ballot) && Arrays.equals(blockHash, ballot.blockHash)
			&& Arrays.equals(signature, ballot.signature);
	}

	/* The ordinal, not the enum: an enum hashes by identity, which differs between runs, and the simulator must
	   replay the same run from the same seed */
	@Override
	public int hashCode() {
		return 31 * (31 * Objects.hash(type.ordinal(), height, view, validator) + Arrays.hashCode(blockHash))
			+ Arrays.hashCode(signature);
	}

	@Override
	public String toString() {
		return "Ballot[" + type + ", height=" + height + ", view=" + view + ", validator=" + validator + ", block="
			+ (isNil() ? "nil" : Bytes.hex(blockHash).substring(0, 8)) + "]";
	}

	private boolean sameVote(Ballot other) {
		return type == other.type && height == other.height && view == other.view
			&& validator.equals(other.validator);
	}

	private static byte[] prevotePayload(byte[] chainId, long height, int view, byte[] hash) {
		return Digests.sha512(PREVOTE_TAG, chainId, Bytes.longBytes(height), Bytes.intBytes(view), hash);
	}
}
