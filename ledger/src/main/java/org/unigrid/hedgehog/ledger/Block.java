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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;

/* A block is its header, its transactions and the certificate: the validators' signatures that commit the block
   in one view of its height. The hash covers the header only, so a block has one identity however its
   signatures were gathered, and in whichever view they were made. The header also commits to the certificate of
   the block before it, its last commit. */
@Value
public class Block {
	public static final int MAX_TRANSACTIONS = 1000;
	public static final int HEADER_SIZE = Long.BYTES + Integer.BYTES + Digests.HASH_SIZE + Long.BYTES
		+ Digests.HASH_SIZE + Digests.HASH_SIZE + Digests.HASH_SIZE + AccountKey.SIZE;

	private final long height;
	private final int view;
	@Getter(AccessLevel.NONE) private final byte[] previousHash;
	private final long time;
	@Getter(AccessLevel.NONE) private final byte[] stateRoot;
	@Getter(AccessLevel.NONE) private final byte[] transactionRoot;
	private final List<Transaction> transactions;
	private final AccountKey proposer;
	private final Certificate certificate;
	private final Certificate lastCommit;
	@Getter(AccessLevel.NONE) private final byte[] lastCommitHash;

	@Builder(toBuilder = true)
	@SuppressWarnings("checkstyle:ParameterNumber")
	public Block(long height, int view, byte[] previousHash, long time, byte[] stateRoot, byte[] transactionRoot,
		List<Transaction> transactions, AccountKey proposer, Certificate certificate, Certificate lastCommit) {
		if (view < 0) {
			throw new IllegalArgumentException("A view is 0 or more, found " + view);
		}

		this.height = height;
		this.view = view;
		this.previousHash = Bytes.requireSize(previousHash, Digests.HASH_SIZE, "A previous block hash");
		this.time = time;
		this.stateRoot = Bytes.requireSize(stateRoot, Digests.HASH_SIZE, "A state root");
		this.transactionRoot = Bytes.requireSize(transactionRoot, Digests.HASH_SIZE, "A transaction root");
		this.transactions = List.copyOf(transactions);
		this.proposer = proposer;
		this.certificate = certificate == null ? Certificate.EMPTY : certificate;
		this.lastCommit = lastCommit == null ? Certificate.EMPTY : lastCommit;
		this.lastCommitHash = this.lastCommit.hash();
	}

	public static class BlockBuilder {
		/* The endorsements of a certificate in the view the builder holds, which is 0 until one is set */
		public BlockBuilder endorsements(List<Endorsement> endorsements) {
			return certificate(new Certificate(certificate == null ? 0 : certificate.view(), endorsements));
		}
	}

	public byte[] getPreviousHash() {
		return previousHash.clone();
	}

	public byte[] getStateRoot() {
		return stateRoot.clone();
	}

	public byte[] getTransactionRoot() {
		return transactionRoot.clone();
	}

	public byte[] getLastCommitHash() {
		return lastCommitHash.clone();
	}

	public List<Endorsement> getEndorsements() {
		return certificate.endorsements();
	}

	public byte[] headerBytes() {
		return ByteBuffer.allocate(HEADER_SIZE).putLong(height).putInt(view).put(previousHash).putLong(time)
			.put(stateRoot).put(transactionRoot).put(lastCommitHash).put(proposer.bytes()).array();
	}

	public byte[] hash() {
		return Digests.sha512(headerBytes());
	}

	/* Kept sorted by signer, so the same signatures give the same block bytes whatever order they arrived in */
	public Block endorsedBy(byte[] validatorSeed, byte[] chainId) {
		final AccountKey signer = Ed25519.publicKey(validatorSeed);

		if (certificate.endorsements().stream().anyMatch(endorsement -> endorsement.signer().equals(signer))) {
			throw new IllegalArgumentException("Validator " + signer + " has already endorsed this block");
		}

		final List<Endorsement> all = new ArrayList<>(certificate.endorsements());

		all.add(new Endorsement(signer, Precommit.sign(validatorSeed, chainId, height, certificate.view(), hash())));
		all.sort(Comparator.comparing(Endorsement::signer));
		return toBuilder().certificate(new Certificate(certificate.view(), all)).build();
	}

	public boolean isSigned(Endorsement endorsement, byte[] chainId) {
		return Precommit.isSigned(endorsement, chainId, height, certificate.view(), hash());
	}

	public static byte[] transactionRootOf(List<Transaction> transactions) {
		return MerkleRoot.of(transactions.stream().map(Transaction::id).toList());
	}
}
