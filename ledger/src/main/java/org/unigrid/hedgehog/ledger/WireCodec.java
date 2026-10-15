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
import java.util.function.Function;

/* The bytes of what nodes send one another, each object alone and nothing around it: a status, a block request, a
   batch of blocks, a heartbeat, a transaction and a consensus message. A decoder takes exactly the bytes of one
   object and refuses anything else with an IllegalArgumentException, whatever the bytes are. Every count and length
   is checked against its bound before any byte is taken for it, and a list grows one decoded entry at a time, so
   a forged count costs nothing until the bytes it promises are really there. */
public final class WireCodec {
	/* The proof of a proposal is bounded by the largest validator set there can be; the engine checks the real set */
	public static final int MAX_MESSAGE_SIZE = ConsensusCodec.maxEncodedSize(ValidatorSet.MAX_SIZE);
	private static final int STATUS_SIZE = Digests.HASH_SIZE + Long.BYTES + Digests.HASH_SIZE;
	private static final int REQUEST_SIZE = Long.BYTES + Integer.BYTES;
	private static final int HEARTBEAT_SIZE = AccountKey.SIZE + Long.BYTES + Digests.HASH_SIZE + Long.BYTES
		+ Ed25519.SIGNATURE_SIZE;

	private WireCodec() {
		/* Static helpers only */
	}

	public static byte[] encode(Status status) {
		return ByteBuffer.allocate(STATUS_SIZE).put(status.chainId()).putLong(status.height())
			.put(status.tipHash()).array();
	}

	public static Status decodeStatus(byte[] data) {
		return decode(data, "status", in -> new Status(Bytes.take(in, Digests.HASH_SIZE), Bytes.takeLong(in),
			Bytes.take(in, Digests.HASH_SIZE)));
	}

	public static byte[] encode(BlockRequest request) {
		return ByteBuffer.allocate(REQUEST_SIZE).putLong(request.from()).putInt(request.max()).array();
	}

	public static BlockRequest decodeBlockRequest(byte[] data) {
		return decode(data, "block request", in -> new BlockRequest(Bytes.takeLong(in), Bytes.takeInt(in)));
	}

	/* The count, then each block as its length and its bytes, in the layout of a block in a decision */
	public static byte[] encode(BlockBatch batch) {
		final byte[] count = ByteBuffer.allocate(Short.BYTES).putShort((short) batch.blocks().size()).array();
		final byte[][] blocks = batch.blocks().stream().map(ConsensusCodec::encodeBlock).toArray(byte[][]::new);

		return Bytes.concat(count, Bytes.concat(blocks));
	}

	public static BlockBatch decodeBlockBatch(byte[] data) {
		return decodeBlockBatch(data, BlockBatch.MAX_BYTES);
	}

	static BlockBatch decodeBlockBatch(byte[] data, int maxBytes) {
		return decode(data, "batch", in -> readBatch(in, maxBytes));
	}

	public static byte[] encode(Heartbeat heartbeat) {
		return ByteBuffer.allocate(HEARTBEAT_SIZE).put(heartbeat.validator().bytes()).putLong(heartbeat.height())
			.put(heartbeat.tipHash()).putLong(heartbeat.time()).put(heartbeat.signature()).array();
	}

	public static Heartbeat decodeHeartbeat(byte[] data) {
		return decode(data, "heartbeat", in -> new Heartbeat(new AccountKey(Bytes.take(in, AccountKey.SIZE)),
			Bytes.takeLong(in), Bytes.take(in, Digests.HASH_SIZE), Bytes.takeLong(in),
			Bytes.take(in, Ed25519.SIGNATURE_SIZE)));
	}

	/* The encoding of a transaction alone is Transaction.encode() */
	public static Transaction decodeTransaction(byte[] data) {
		return decode(data, "transaction", TransactionCodec::decode);
	}

	public static byte[] encodeMessage(Message message) {
		return ConsensusCodec.encode(message);
	}

	public static Message decodeMessage(byte[] data) {
		return ConsensusCodec.decode(data, ValidatorSet.MAX_SIZE);
	}

	private static <T> T decode(byte[] data, String name, Function<ByteBuffer, T> reader) {
		final ByteBuffer in = ByteBuffer.wrap(data);
		final T value = reader.apply(in);

		if (in.hasRemaining()) {
			throw new IllegalArgumentException(in.remaining() + " bytes follow the " + name);
		}

		return value;
	}

	private static BlockBatch readBatch(ByteBuffer in, int maxBytes) {
		final int count = Bytes.takeUnsignedShort(in);

		if (count > BlockBatch.MAX_BLOCKS) {
			throw new IllegalArgumentException("A batch holds at most " + BlockBatch.MAX_BLOCKS
				+ " blocks, found " + count);
		}

		final List<Block> blocks = new ArrayList<>();
		int room = maxBytes;

		for (int i = 0; i < count; i++) {
			final int length = takeBlockLength(in);

			if (length > room) {
				throw new IllegalArgumentException("A batch holds at most " + maxBytes + " bytes of blocks");
			}

			room -= length;
			blocks.add(BlockCodec.decode(Bytes.take(in, length)));
		}

		return new BlockBatch(blocks);
	}

	private static int takeBlockLength(ByteBuffer in) {
		final int length = Bytes.takeInt(in);

		if (length < 1 || length > BlockCodec.MAX_ENCODED_SIZE) {
			throw new IllegalArgumentException("A block is 1 to " + BlockCodec.MAX_ENCODED_SIZE
				+ " bytes, found " + length);
		}

		return length;
	}
}
