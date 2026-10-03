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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/* A block is its header, its transactions and the certificate: the validators' signatures over the hash of
   the header. The hash leaves the certificate out, so a block has one identity however its signatures were
   gathered. */
@Value
public class Block {
	public static final int MAX_TRANSACTIONS = 1000;
	public static final int HEADER_SIZE = Long.BYTES + Digests.HASH_SIZE + Long.BYTES + Digests.HASH_SIZE
		+ Digests.HASH_SIZE + AccountKey.SIZE;

	private final long height;
	private final byte[] previousHash;
	private final long time;
	private final byte[] stateRoot;
	private final byte[] transactionRoot;
	private final List<Transaction> transactions;
	private final AccountKey proposer;
	private final List<Endorsement> endorsements;

	@Builder(toBuilder = true)
	@SuppressWarnings("checkstyle:ParameterNumber")
	public Block(long height, byte[] previousHash, long time, byte[] stateRoot, byte[] transactionRoot,
		List<Transaction> transactions, AccountKey proposer, List<Endorsement> endorsements) {
		this.height = height;
		this.previousHash = Bytes.requireSize(previousHash, Digests.HASH_SIZE, "A previous block hash");
		this.time = time;
		this.stateRoot = Bytes.requireSize(stateRoot, Digests.HASH_SIZE, "A state root");
		this.transactionRoot = Bytes.requireSize(transactionRoot, Digests.HASH_SIZE, "A transaction root");
		this.transactions = List.copyOf(transactions);
		this.proposer = proposer;
		this.endorsements = List.copyOf(endorsements);
	}

	public byte[] headerBytes() {
		return ByteBuffer.allocate(HEADER_SIZE).putLong(height).put(previousHash).putLong(time).put(stateRoot)
			.put(transactionRoot).put(proposer.bytes()).array();
	}

	public byte[] hash() {
		return Digests.sha512(headerBytes());
	}

	/* Kept sorted by signer, so the same signatures give the same block bytes whatever order they arrived in */
	public Block endorsedBy(byte[] validatorSeed) {
		final AccountKey signer = Ed25519.publicKey(validatorSeed);

		if (endorsements.stream().anyMatch(endorsement -> endorsement.signer().equals(signer))) {
			throw new IllegalArgumentException("Validator " + signer + " has already endorsed this block");
		}

		final List<Endorsement> all = new ArrayList<>(endorsements);

		all.add(new Endorsement(signer, Ed25519.sign(validatorSeed, hash())));
		all.sort(Comparator.comparing(Endorsement::signer));
		return toBuilder().endorsements(all).build();
	}

	public boolean isSigned(Endorsement endorsement) {
		return Ed25519.verify(endorsement.signer(), hash(), endorsement.signature());
	}

	public static byte[] transactionRootOf(List<Transaction> transactions) {
		return MerkleRoot.of(transactions.stream().map(Transaction::id).toList());
	}
}
