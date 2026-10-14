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
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/* The proposer of a view puts a block forward. `validRound` is -1 for a block that is new, or the earlier view in
   which the block already got more than 2/3 of the prevotes, when the proposer puts it forward again; the block
   keeps its own header, so it keeps its identity. The sender signs the view, the valid round, the block hash and a
   digest of `polka`, the prevotes that prove that quorum, for a node that never saw them. The proof is therefore
   part of what the sender signed: a relay that strips it or changes it, to get a copy that only seems to be as good
   as the original delivered first, breaks the signature and the copy is dropped. For the same reason an honest
   proposer builds one proposal per view and resends exactly those bytes, never the same view with another proof. */
public record Proposal(long height, int view, int validRound, Block block, List<Ballot> polka, AccountKey sender,
	byte[] signature) implements Message {
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

		polka = List.copyOf(polka);
		requireProofOf(height, validRound, block, polka);
		signature = Bytes.requireSize(signature, Ed25519.SIGNATURE_SIZE, "A proposal signature");
	}

	public static Proposal signed(byte[] seed, byte[] chainId, long height, int view, int validRound, Block block) {
		return signed(seed, chainId, height, view, validRound, block, List.of());
	}

	public static Proposal signed(byte[] seed, byte[] chainId, long height, int view, int validRound, Block block,
		List<Ballot> polka) {
		return new Proposal(height, view, validRound, block, polka, Ed25519.publicKey(seed),
			Ed25519.sign(seed, payload(chainId, height, view, validRound, block.hash(), polka)));
	}

	public boolean hasValidSignature(byte[] chainId) {
		return Ed25519.verify(sender, payload(chainId, height, view, validRound, block.hash(), polka), signature);
	}

	@Override
	public byte[] signature() {
		return signature.clone();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Proposal proposal && sameRound(proposal) && block.equals(proposal.block)
			&& polka.equals(proposal.polka) && sender.equals(proposal.sender)
			&& Arrays.equals(signature, proposal.signature);
	}

	@Override
	public int hashCode() {
		return 31 * Objects.hash(height, view, validRound, block, polka, sender) + Arrays.hashCode(signature);
	}

	private boolean sameRound(Proposal other) {
		return height == other.height && view == other.view && validRound == other.validRound;
	}

	private static void requireProofOf(long height, int validRound, Block block, List<Ballot> polka) {
		if (polka.isEmpty()) {
			return;
		}

		if (polka.size() > ValidatorSet.MAX_SIZE) {
			throw new IllegalArgumentException("A proof holds at most " + ValidatorSet.MAX_SIZE
				+ " ballots, found " + polka.size());
		}

		if (validRound == -1) {
			throw new IllegalArgumentException("A new block has no earlier prevotes to prove");
		}

		final byte[] blockHash = block.hash();

		polka.forEach(ballot -> requireOnePrevoteOf(ballot, height, validRound, blockHash));
	}

	private static void requireOnePrevoteOf(Ballot ballot, long height, int validRound, byte[] blockHash) {
		if (ballot.type() != Ballot.Type.PREVOTE || ballot.height() != height || ballot.view() != validRound
			|| !Arrays.equals(ballot.blockHash(), blockHash)) {
			throw new IllegalArgumentException("A proof holds only prevotes of height " + height + ", view "
				+ validRound + " and the hash of the block, found " + ballot);
		}
	}

	private static byte[] payload(byte[] chainId, long height, int view, int validRound, byte[] blockHash,
		List<Ballot> polka) {
		return Digests.sha512(TAG, chainId, Bytes.longBytes(height), Bytes.intBytes(view),
			Bytes.intBytes(validRound), blockHash, proofDigest(polka));
	}

	/* A ballot of the proof is settled by its validator and its signature, as the rest is fixed by the proposal, and
	   both have a fixed size, so the plain concatenation in order cannot be read in two ways */
	private static byte[] proofDigest(List<Ballot> polka) {
		final byte[][] parts = polka.stream()
			.flatMap(ballot -> Stream.of(ballot.validator().bytes(), ballot.signature())).toArray(byte[][]::new);

		return Digests.sha512(parts);
	}
}
