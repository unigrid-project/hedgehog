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

/* The proposer of a view puts a block forward. `validRound` is -1 for a block that is new, or the earlier view in
   which the block already got more than 2/3 of the prevotes, when the proposer puts it forward again; the block
   keeps its own header, so it keeps its identity. The sender signs the view, the valid round and the block hash. */
public record Proposal(long height, int view, int validRound, Block block, AccountKey sender, byte[] signature)
	implements Message {
	private static final byte[] TAG = "Unigrid proposal v1".getBytes(StandardCharsets.US_ASCII);

	public Proposal {
		Objects.requireNonNull(block);
		Objects.requireNonNull(sender);

		if (height < 1 || view < 0 || validRound < -1 || validRound >= view) {
			throw new IllegalArgumentException("A proposal has a height from 1, a view from 0 and a valid round "
				+ "from -1 below its view, found " + height + ", " + view + " and " + validRound);
		}

		if (block.getHeight() != height) {
			throw new IllegalArgumentException("The block is of height " + block.getHeight()
				+ ", not " + height);
		}

		signature = Bytes.requireSize(signature, Ed25519.SIGNATURE_SIZE, "A proposal signature");
	}

	public static Proposal signed(byte[] seed, byte[] chainId, long height, int view, int validRound, Block block) {
		return new Proposal(height, view, validRound, block, Ed25519.publicKey(seed),
			Ed25519.sign(seed, payload(chainId, height, view, validRound, block.hash())));
	}

	public boolean hasValidSignature(byte[] chainId) {
		return Ed25519.verify(sender, payload(chainId, height, view, validRound, block.hash()), signature);
	}

	@Override
	public byte[] signature() {
		return signature.clone();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Proposal proposal && sameRound(proposal) && block.equals(proposal.block)
			&& sender.equals(proposal.sender) && Arrays.equals(signature, proposal.signature);
	}

	@Override
	public int hashCode() {
		return 31 * Objects.hash(height, view, validRound, block, sender) + Arrays.hashCode(signature);
	}

	private boolean sameRound(Proposal other) {
		return height == other.height && view == other.view && validRound == other.validRound;
	}

	private static byte[] payload(byte[] chainId, long height, int view, int validRound, byte[] blockHash) {
		return Digests.sha512(TAG, chainId, Bytes.longBytes(height), Bytes.intBytes(view),
			Bytes.intBytes(validRound), blockHash);
	}
}
