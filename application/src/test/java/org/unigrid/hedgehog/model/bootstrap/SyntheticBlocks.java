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

package org.unigrid.hedgehog.model.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/*
   Writes block files that have the same framing and header layout as the legacy daemon's, so branches
   and reorganisations can be set up deliberately rather than hunted for in the real chain. A block
   normally carries no transactions, which is all the chain linker ever looks at, but a caller of
   mainChain can supply real coinbase and spending transactions where the ledger replay needs them.
*/
public final class SyntheticBlocks {
	public static final int BASE_TIME = 1536062400;
	public static final int BLOCK_INTERVAL = 60;

	private static final int NULL_OUTPUT_INDEX = -1;
	private static final int TRANSACTION_VERSION = 1;
	private static final byte OP_DUP = (byte) 0x76;
	private static final byte OP_HASH160 = (byte) 0xa9;
	private static final byte OP_EQUALVERIFY = (byte) 0x88;
	private static final byte OP_CHECKSIG = (byte) 0xac;

	private SyntheticBlocks() {
		/* Empty on purpose */
	}

	public static int timeAt(int height) {
		return BASE_TIME + height * BLOCK_INTERVAL;
	}

	public static BlockFileStore store(Consumer<Builder> definition) throws IOException {
		final Builder builder = new Builder();

		definition.accept(builder);
		return BlockFileStore.open(builder.write());
	}

	public static byte[] coinbaseTransaction(byte[] addressHash, long value) {
		return transaction(List.of(input(new byte[Hashing.HASH_SIZE], NULL_OUTPUT_INDEX)),
			List.of(output(addressHash, value)));
	}

	public static byte[] spendTransaction(byte[] previousTransaction, int previousIndex,
		byte[] addressHash, long value) {

		return transaction(List.of(input(previousTransaction, previousIndex)), List.of(output(addressHash, value)));
	}

	public static byte[] transactionId(byte[] transaction) {
		return Hashing.doubleSha256(ByteBuffer.wrap(transaction));
	}

	private static byte[] transaction(List<byte[]> inputs, List<byte[]> outputs) {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		final ByteBuffer version = ByteBuffer.allocate(Integer.BYTES)
			.order(ByteOrder.LITTLE_ENDIAN).putInt(TRANSACTION_VERSION);

		buffer.writeBytes(version.array());
		writeCounted(buffer, inputs);
		writeCounted(buffer, outputs);
		buffer.writeBytes(new byte[Integer.BYTES]);
		return buffer.toByteArray();
	}

	private static void writeCounted(ByteArrayOutputStream buffer, List<byte[]> items) {
		final ByteBuffer count = ByteBuffer.allocate(Long.BYTES + 1).order(ByteOrder.LITTLE_ENDIAN);

		VarInt.write(count, items.size());
		buffer.writeBytes(Arrays.copyOf(count.array(), count.position()));
		items.forEach(buffer::writeBytes);
	}

	private static byte[] input(byte[] previousTransaction, int previousIndex) {
		final ByteBuffer input = ByteBuffer.allocate(Hashing.HASH_SIZE + 2 * Integer.BYTES + 1)
			.order(ByteOrder.LITTLE_ENDIAN);

		input.put(previousTransaction).putInt(previousIndex).put((byte) 0).putInt(-1);
		return input.array();
	}

	private static byte[] output(byte[] addressHash, long value) {
		final byte[] script = publicKeyHashScript(addressHash);
		final ByteBuffer output = ByteBuffer.allocate(Long.BYTES + 1 + script.length)
			.order(ByteOrder.LITTLE_ENDIAN);

		output.putLong(value).put((byte) script.length).put(script);
		return output.array();
	}

	private static byte[] publicKeyHashScript(byte[] addressHash) {
		return ByteBuffer.allocate(Hashing.ADDRESS_HASH_SIZE + 5).put(OP_DUP).put(OP_HASH160)
			.put((byte) Hashing.ADDRESS_HASH_SIZE).put(addressHash).put(OP_EQUALVERIFY).put(OP_CHECKSIG).array();
	}

	public static final class Builder {
		private static final int MERKLE_OFFSET = 36;
		private static final int TIME_OFFSET = 68;
		private static final int VERSION_WITH_ACCUMULATOR = 4;

		private final ByteArrayOutputStream records = new ByteArrayOutputStream();
		private final List<byte[]> mainChain = new ArrayList<>();
		private int nonce;

		public void mainChain(int blocksAfterGenesis) {
			mainChain(blocksAfterGenesis, height -> List.of());
		}

		public void mainChain(int blocksAfterGenesis, IntFunction<List<byte[]>> transactionsAt) {
			mainChain.add(append(1, new byte[Hashing.HASH_SIZE], timeAt(0), transactionsAt.apply(0)));

			for (int height = 1; height <= blocksAfterGenesis; height++) {
				mainChain.add(append(VERSION_WITH_ACCUMULATOR, mainChain.get(height - 1), timeAt(height),
					transactionsAt.apply(height)));
			}
		}

		public void fork(int fromHeight, int length) {
			byte[] previous = mainChain.get(fromHeight);

			for (int i = 1; i <= length; i++) {
				previous = append(VERSION_WITH_ACCUMULATOR, previous, timeAt(fromHeight + i), List.of());
			}
		}

		private byte[] append(int version, byte[] previousHash, int time, List<byte[]> transactions) {
			final byte[] header = header(version, previousHash, time);
			final byte[] payload = transactionPayload(transactions);
			final ByteBuffer record = ByteBuffer.allocate(header.length + payload.length + 8)
				.order(ByteOrder.LITTLE_ENDIAN);

			record.putInt(BlockFileStore.MAGIC).putInt(header.length + payload.length).put(header).put(payload);
			records.write(record.array(), 0, record.position());

			return version < VERSION_WITH_ACCUMULATOR
				? BlockParser.GENESIS_HASH : Hashing.doubleSha256(ByteBuffer.wrap(header));
		}

		private byte[] transactionPayload(List<byte[]> transactions) {
			final ByteArrayOutputStream payload = new ByteArrayOutputStream();

			writeCounted(payload, transactions);
			return payload.toByteArray();
		}

		private byte[] header(int version, byte[] previousHash, int time) {
			final ByteBuffer header = ByteBuffer.allocate(BlockParser.headerSize(version))
				.order(ByteOrder.LITTLE_ENDIAN);

			header.putInt(version).put(previousHash);
			header.putInt(MERKLE_OFFSET, ++nonce);
			header.putInt(TIME_OFFSET, time);
			header.putInt(TIME_OFFSET + Integer.BYTES, 0x1e0fffff);
			header.putInt(TIME_OFFSET + 2 * Integer.BYTES, nonce);
			return header.array();
		}

		private Path write() throws IOException {
			final Path directory = Files.createTempDirectory("hhg-synthetic-");

			Files.write(directory.resolve("blk00000.dat"), records.toByteArray());
			directory.toFile().deleteOnExit();
			return directory;
		}
	}
}
