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
import java.util.ArrayList;
import java.util.List;

/* The header, then the certificate (a count and the endorsements), then the transactions (a count and
   each transaction). Lists are grown one decoded entry at a time, never sized from a count read off the
   wire, so a forged count costs nothing until the bytes it promises are really there. */
public final class BlockCodec {
	private BlockCodec() {
		/* Static helpers only */
	}

	public static byte[] encode(Block block) {
		final List<byte[]> transactions = block.getTransactions().stream().map(Transaction::encode).toList();
		final int size = Block.HEADER_SIZE + Short.BYTES
			+ block.getEndorsements().size() * (AccountKey.SIZE + Ed25519.SIGNATURE_SIZE) + Short.BYTES
			+ transactions.stream().mapToInt(encoded -> encoded.length).sum();
		final ByteBuffer out = ByteBuffer.allocate(size);

		out.put(block.headerBytes()).putShort((short) block.getEndorsements().size());
		block.getEndorsements().forEach(endorsement -> out.put(endorsement.signer().bytes())
			.put(endorsement.signature()));
		out.putShort((short) transactions.size());
		transactions.forEach(out::put);
		return out.array();
	}

	public static Block decode(byte[] data) {
		final ByteBuffer in = ByteBuffer.wrap(data);
		final long height = Bytes.takeLong(in);
		final byte[] previousHash = Bytes.take(in, Digests.HASH_SIZE);
		final long time = Bytes.takeLong(in);
		final byte[] stateRoot = Bytes.take(in, Digests.HASH_SIZE);
		final byte[] transactionRoot = Bytes.take(in, Digests.HASH_SIZE);
		final AccountKey proposer = new AccountKey(Bytes.take(in, AccountKey.SIZE));
		final List<Endorsement> endorsements = decodeEndorsements(in);
		final List<Transaction> transactions = decodeTransactions(in);

		if (in.hasRemaining()) {
			throw new IllegalArgumentException(in.remaining() + " bytes follow the block");
		}

		return new Block(height, previousHash, time, stateRoot, transactionRoot, transactions, proposer,
			endorsements);
	}

	private static List<Endorsement> decodeEndorsements(ByteBuffer in) {
		final int count = Bytes.takeUnsignedShort(in);

		if (count > ValidatorSet.MAX_SIZE) {
			throw new IllegalArgumentException("Too many endorsements: " + count);
		}

		final List<Endorsement> endorsements = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			endorsements.add(new Endorsement(new AccountKey(Bytes.take(in, AccountKey.SIZE)),
				Bytes.take(in, Ed25519.SIGNATURE_SIZE)));
		}

		return endorsements;
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
