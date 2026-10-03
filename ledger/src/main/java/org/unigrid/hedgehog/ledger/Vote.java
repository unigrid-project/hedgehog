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
import java.util.Arrays;
import java.util.Objects;

/* A validator's vote to add a key to the validator set or to remove one from it. The set changes at the
   end of the round, when more than 2/3 of the set voted for the same change. */
public record Vote(AccountKey voter, AccountKey candidate, Action action, long round, byte[] signature)
	implements Transaction {
	public static final byte KIND = 2;
	public static final int ENCODED_SIZE = 1 + AccountKey.SIZE + AccountKey.SIZE + 1 + Long.BYTES
		+ Ed25519.SIGNATURE_SIZE;
	private static final byte[] TAG = "Unigrid vote v1".getBytes(StandardCharsets.US_ASCII);

	public enum Action {
		ADD((byte) 1), REMOVE((byte) 2);

		private final byte wire;

		Action(byte wire) {
			this.wire = wire;
		}

		public byte wire() {
			return wire;
		}

		public static Action of(byte wire) {
			for (final Action action : values()) {
				if (action.wire == wire) {
					return action;
				}
			}

			throw new IllegalArgumentException("Unknown vote action " + wire);
		}
	}

	public Vote {
		Objects.requireNonNull(voter);
		Objects.requireNonNull(candidate);
		Objects.requireNonNull(action);

		if (round < 0) {
			throw new IllegalArgumentException("A round is 0 or more, found " + round);
		}

		signature = Bytes.requireSize(signature, Ed25519.SIGNATURE_SIZE, "A vote signature");
	}

	public static Vote signed(byte[] voterSeed, AccountKey candidate, Action action, long round, byte[] chainId) {
		final AccountKey voter = Ed25519.publicKey(voterSeed);

		return new Vote(voter, candidate, action, round,
			Ed25519.sign(voterSeed, payload(voter, candidate, action, round, chainId)));
	}

	/* Every field is under the signature, and so is the chain, so a vote cannot be moved to another
	   candidate, action, round or chain */
	public boolean hasValidSignature(byte[] chainId) {
		return Ed25519.verify(voter, payload(voter, candidate, action, round, chainId), signature);
	}

	@Override
	public byte[] signature() {
		return signature.clone();
	}

	@Override
	public byte kind() {
		return KIND;
	}

	@Override
	public byte[] encode() {
		return ByteBuffer.allocate(ENCODED_SIZE).put(KIND).put(voter.bytes()).put(candidate.bytes())
			.put(action.wire).putLong(round).put(signature).array();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Vote vote && voter.equals(vote.voter) && candidate.equals(vote.candidate)
			&& action == vote.action && round == vote.round && Arrays.equals(signature, vote.signature);
	}

	@Override
	public int hashCode() {
		return 31 * Objects.hash(voter, candidate, action, round) + Arrays.hashCode(signature);
	}

	private static byte[] payload(AccountKey voter, AccountKey candidate, Action action, long round,
		byte[] chainId) {
		return Digests.sha512(TAG, chainId, voter.bytes(), candidate.bytes(), new byte[] { action.wire },
			Bytes.longBytes(round));
	}
}
