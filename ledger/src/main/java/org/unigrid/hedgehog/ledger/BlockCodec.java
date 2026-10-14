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
import java.util.Arrays;
import java.util.List;

/* The header, then the certificate and the last commit (each its view, a count and the endorsements), then the
   transactions (a count and each transaction). Lists are grown one decoded entry at a time, never sized from a
   count read off the wire, so a forged count costs nothing until the bytes it promises are really there. */
public final class BlockCodec {
	/* The most an encoded block can hold: two full certificates and a full set of the larger transaction kind */
	public static final int MAX_ENCODED_SIZE = Block.HEADER_SIZE + 2 * Certificate.MAX_ENCODED_SIZE + Short.BYTES
		+ Block.MAX_TRANSACTIONS * Math.max(Vote.ENCODED_SIZE, Claim.MAX_ENCODED_SIZE);

	private BlockCodec() {
		/* Static helpers only */
	}

	public static byte[] encode(Block block) {
		if (block.getTransactions().size() > Block.MAX_TRANSACTIONS) {
			throw new IllegalArgumentException("A block holds at most " + Block.MAX_TRANSACTIONS
				+ " transactions");
		}

		final List<byte[]> transactions = block.getTransactions().stream().map(Transaction::encode).toList();
		final byte[] certificate = block.getCertificate().encode();
		final byte[] lastCommit = block.getLastCommit().encode();
		final int size = Block.HEADER_SIZE + certificate.length + lastCommit.length + Short.BYTES
			+ transactions.stream().mapToInt(encoded -> encoded.length).sum();
		final ByteBuffer out = ByteBuffer.allocate(size);

		out.put(block.headerBytes()).put(certificate).put(lastCommit).putShort((short) transactions.size());
		transactions.forEach(out::put);
		return out.array();
	}

	public static Block decode(byte[] data) {
		final ByteBuffer in = ByteBuffer.wrap(data);
		final long height = Bytes.takeLong(in);
		final int view = Bytes.takeInt(in);
		final byte[] previousHash = Bytes.take(in, Digests.HASH_SIZE);
		final long time = Bytes.takeLong(in);
		final byte[] stateRoot = Bytes.take(in, Digests.HASH_SIZE);
		final byte[] transactionRoot = Bytes.take(in, Digests.HASH_SIZE);
		final byte[] lastCommitHash = Bytes.take(in, Digests.HASH_SIZE);
		final AccountKey proposer = new AccountKey(Bytes.take(in, AccountKey.SIZE));
		final Certificate certificate = Certificate.decode(in);
		final Certificate lastCommit = Certificate.decode(in);

		if (!Arrays.equals(lastCommitHash, lastCommit.hash())) {
			throw new IllegalArgumentException("The header names another last commit than the block holds");
		}

		final List<Transaction> transactions = decodeTransactions(in);

		if (in.hasRemaining()) {
			throw new IllegalArgumentException(in.remaining() + " bytes follow the block");
		}

		return new Block(height, view, previousHash, time, stateRoot, transactionRoot, transactions, proposer,
			certificate, lastCommit);
	}

	private static List<Transaction> decodeTransactions(ByteBuffer in) {
		final int count = Bytes.takeUnsignedShort(in);

		if (count > Block.MAX_TRANSACTIONS) {
			throw new IllegalArgumentException("Too many transactions: " + count);
		}

		final List<Transaction> transactions = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			transactions.add(TransactionCodec.decode(in));
		}

		return transactions;
	}
}
