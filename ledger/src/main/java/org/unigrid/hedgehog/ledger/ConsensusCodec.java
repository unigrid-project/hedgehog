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
import java.util.List;

/* One kind byte, then the message. A ballot is its type, height, view, block hash, validator and signature. A
   proposal is its height, view, valid round, sender and signature, the length of its block and the block, then
   the count of the ballots that prove the valid round and those ballots, each in the layout of a ballot without
   the kind byte. A decision is the length of its block and the block. Every length and count is checked against a
   bound before any byte is taken for it, and the proof list grows one decoded ballot at a time, so a forged
   count costs nothing until the bytes it promises are really there. */
public final class ConsensusCodec {
	private static final byte BALLOT = 1;
	private static final byte PROPOSAL = 2;
	private static final byte DECISION = 3;
	private static final byte PREVOTE_CODE = 0;
	private static final byte PRECOMMIT_CODE = 1;
	private static final int BALLOT_BODY_SIZE = Byte.BYTES + Long.BYTES + Integer.BYTES + Digests.HASH_SIZE
		+ AccountKey.SIZE + Ed25519.SIGNATURE_SIZE;
	static final int BALLOT_SIZE = Byte.BYTES + BALLOT_BODY_SIZE;
	private static final int PROPOSAL_HEAD_SIZE = Byte.BYTES + Long.BYTES + 2 * Integer.BYTES + AccountKey.SIZE
		+ Ed25519.SIGNATURE_SIZE;

	private ConsensusCodec() {
		/* Static helpers only */
	}

	/* A proposal is the longest message, as it holds a block and a proof on top of what a decision holds */
	public static int maxEncodedSize(int largestProof) {
		return PROPOSAL_HEAD_SIZE + Integer.BYTES + BlockCodec.MAX_ENCODED_SIZE + Short.BYTES
			+ largestProof * BALLOT_BODY_SIZE;
	}

	public static byte[] encode(Message message) {
		return switch (message) {
			case Ballot ballot -> Bytes.concat(new byte[] { BALLOT }, encodeBallot(ballot));
			case Proposal proposal -> encodeProposal(proposal);
			case Decision decision -> Bytes.concat(new byte[] { DECISION }, encodeBlock(decision.block()));
		};
	}

	public static void encode(Message message, ByteBuffer out) {
		out.put(encode(message));
	}

	public static Message decode(byte[] data, int largestProof) {
		final ByteBuffer in = ByteBuffer.wrap(data);
		final Message message = decode(in, largestProof);

		if (in.hasRemaining()) {
			throw new IllegalArgumentException(in.remaining() + " bytes follow the message");
		}

		return message;
	}

	/* After a refused message the position of the buffer means nothing, so the caller drops the buffer */
	public static Message decode(ByteBuffer in, int largestProof) {
		final byte kind = Bytes.take(in, 1)[0];

		return switch (kind) {
			case BALLOT -> decodeBallot(in);
			case PROPOSAL -> decodeProposal(in, largestProof);
			case DECISION -> new Decision(decodeBlock(in));
			default -> throw new IllegalArgumentException("Unknown message kind " + Byte.toUnsignedInt(kind));
		};
	}

	private static byte[] encodeBallot(Ballot ballot) {
		return ByteBuffer.allocate(BALLOT_BODY_SIZE).put(encodeType(ballot.type())).putLong(ballot.height())
			.putInt(ballot.view()).put(ballot.blockHash()).put(ballot.validator().bytes())
			.put(ballot.signature()).array();
	}

	private static byte encodeType(Ballot.Type type) {
		return type == Ballot.Type.PREVOTE ? PREVOTE_CODE : PRECOMMIT_CODE;
	}

	static byte[] encodeBlock(Block block) {
		final byte[] encoded = BlockCodec.encode(block);

		return Bytes.concat(Bytes.intBytes(encoded.length), encoded);
	}

	private static byte[] encodeProposal(Proposal proposal) {
		final byte[] head = ByteBuffer.allocate(PROPOSAL_HEAD_SIZE).put(PROPOSAL).putLong(proposal.height())
			.putInt(proposal.view()).putInt(proposal.validRound()).put(proposal.sender().bytes())
			.put(proposal.signature()).array();
		final byte[] count = ByteBuffer.allocate(Short.BYTES).putShort((short) proposal.polka().size()).array();
		final byte[] proof = Bytes.concat(proposal.polka().stream().map(ConsensusCodec::encodeBallot)
			.toArray(byte[][]::new));

		return Bytes.concat(head, encodeBlock(proposal.block()), count, proof);
	}

	private static Ballot decodeBallot(ByteBuffer in) {
		final Ballot.Type type = decodeType(Bytes.take(in, 1)[0]);
		final long height = Bytes.takeLong(in);
		final int view = Bytes.takeInt(in);
		final byte[] blockHash = Bytes.take(in, Digests.HASH_SIZE);
		final AccountKey validator = new AccountKey(Bytes.take(in, AccountKey.SIZE));

		return new Ballot(type, height, view, blockHash, validator, Bytes.take(in, Ed25519.SIGNATURE_SIZE));
	}

	private static Ballot.Type decodeType(byte code) {
		return switch (code) {
			case PREVOTE_CODE -> Ballot.Type.PREVOTE;
			case PRECOMMIT_CODE -> Ballot.Type.PRECOMMIT;
			default -> throw new IllegalArgumentException("Unknown ballot type " + Byte.toUnsignedInt(code));
		};
	}

	private static Proposal decodeProposal(ByteBuffer in, int largestProof) {
		final long height = Bytes.takeLong(in);
		final int view = Bytes.takeInt(in);
		final int validRound = Bytes.takeInt(in);
		final AccountKey sender = new AccountKey(Bytes.take(in, AccountKey.SIZE));
		final byte[] signature = Bytes.take(in, Ed25519.SIGNATURE_SIZE);
		final Block block = decodeBlock(in);

		return new Proposal(height, view, validRound, block, decodeProof(in, largestProof), sender, signature);
	}

	private static List<Ballot> decodeProof(ByteBuffer in, int largestProof) {
		final int count = Bytes.takeUnsignedShort(in);

		if (count > largestProof) {
			throw new IllegalArgumentException("A proof holds at most " + largestProof
				+ " ballots, found " + count);
		}

		final List<Ballot> polka = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			polka.add(decodeBallot(in));
		}

		return polka;
	}

	static Block decodeBlock(ByteBuffer in) {
		return BlockCodec.decode(Bytes.take(in, takeBlockLength(in)));
	}

	/* The length in front of an encoded block, checked before the bytes of the block are taken */
	static int takeBlockLength(ByteBuffer in) {
		final int length = Bytes.takeInt(in);

		if (length < 1 || length > BlockCodec.MAX_ENCODED_SIZE) {
			throw new IllegalArgumentException("A block is 1 to " + BlockCodec.MAX_ENCODED_SIZE
				+ " bytes, found " + length);
		}

		return length;
	}
}
