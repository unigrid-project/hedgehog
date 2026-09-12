/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/*
   The genesis block is hashed with Quark rather than SHA-256, so its hash cannot be derived from
   the header the way every later block's can. It is pinned here in internal byte order, which is the
   reverse of the form block explorers display.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BlockParser {
	public static final byte[] GENESIS_HASH = reverse(HexFormat.of()
		.parseHex("00000416490cfdea94f5f78bc14285e48c78c42ea8ec9c8a623050d0175cf2d2"));

	private static final int BASE_HEADER_SIZE = 80;
	private static final int ACCUMULATOR_CHECKPOINT_SIZE = 32;
	private static final int ACCUMULATOR_FROM_VERSION = 4;
	private static final int NULL_OUTPUT_INDEX = 0xffffffff;

	public static BlockHeader header(ByteBuffer block) {
		final int start = block.position();
		final int version = block.getInt();
		final byte[] previousHash = readBytes(block, Hashing.HASH_SIZE);
		final byte[] merkleRoot = readBytes(block, Hashing.HASH_SIZE);
		final int time = block.getInt();
		final int bits = block.getInt();
		final int nonce = block.getInt();

		block.position(start + headerSize(version));

		return BlockHeader.builder().version(version).previousHash(previousHash).merkleRoot(merkleRoot)
			.time(time).bits(bits).nonce(nonce)
			.hash(hash(block, start, version, previousHash)).build();
	}

	public static List<LegacyTransaction> transactions(ByteBuffer block) {
		final int count = VarInt.readCount(block);
		final List<LegacyTransaction> transactions = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			transactions.add(transaction(block));
		}

		return transactions;
	}

	public static int headerSize(int version) {
		return version >= ACCUMULATOR_FROM_VERSION
			? BASE_HEADER_SIZE + ACCUMULATOR_CHECKPOINT_SIZE : BASE_HEADER_SIZE;
	}

	private static LegacyTransaction transaction(ByteBuffer block) {
		final int start = block.position();

		skipVersion(block);

		final List<TransactionInput> inputs = inputs(block);
		final List<TransactionOutput> outputs = outputs(block);

		skipLockTime(block);

		return LegacyTransaction.builder().id(identifier(block, start)).inputs(inputs).outputs(outputs).build();
	}

	private static List<TransactionInput> inputs(ByteBuffer block) {
		final int count = VarInt.readCount(block);
		final List<TransactionInput> inputs = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			final byte[] previousTransaction = readBytes(block, Hashing.HASH_SIZE);
			final int previousIndex = block.getInt();
			final byte[] signatureScript = readBytes(block, VarInt.readCount(block));

			skipSequence(block);
			inputs.add(TransactionInput.builder().previousTransaction(previousTransaction)
				.previousIndex(previousIndex)
				.type(inputType(previousTransaction, previousIndex, signatureScript)).build());
		}

		return inputs;
	}

	private static List<TransactionOutput> outputs(ByteBuffer block) {
		final int count = VarInt.readCount(block);
		final List<TransactionOutput> outputs = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			final long value = block.getLong();
			final byte[] script = readBytes(block, VarInt.readCount(block));
			final byte[] addressHash = ScriptAddressResolver.resolve(script);

			outputs.add(TransactionOutput.builder().value(value).addressHash(addressHash)
				.type(outputType(script, addressHash)).build());
		}

		return outputs;
	}

	/*
	   A zerocoin spend carries the same null previous output as a coinbase, so it has to be
	   recognised by its signature script before the coinbase test runs.
	*/
	private static InputType inputType(byte[] previousTransaction, int previousIndex, byte[] signatureScript) {
		if (ScriptAddressResolver.isZerocoinSpend(signatureScript)) {
			return InputType.ZEROCOIN_SPEND;
		}

		if (previousIndex == NULL_OUTPUT_INDEX && isZero(previousTransaction)) {
			return InputType.COINBASE;
		}

		return InputType.STANDARD;
	}

	private static OutputType outputType(byte[] script, byte[] addressHash) {
		if (addressHash != null) {
			return OutputType.ADDRESS;
		}

		if (script.length == 0) {
			return OutputType.EMPTY;
		}

		return ScriptAddressResolver.isZerocoinMint(script) ? OutputType.ZEROCOIN_MINT : OutputType.UNSPENDABLE;
	}

	private static byte[] hash(ByteBuffer block, int start, int version, byte[] previousHash) {
		return isZero(previousHash) ? GENESIS_HASH
			: Hashing.doubleSha256(block.duplicate().position(start).limit(start + headerSize(version)));
	}

	private static byte[] identifier(ByteBuffer block, int start) {
		return Hashing.doubleSha256(block.duplicate().position(start).limit(block.position()));
	}

	private static void skipVersion(ByteBuffer block) {
		block.position(block.position() + Integer.BYTES);
	}

	private static void skipLockTime(ByteBuffer block) {
		block.position(block.position() + Integer.BYTES);
	}

	private static void skipSequence(ByteBuffer block) {
		block.position(block.position() + Integer.BYTES);
	}

	private static byte[] readBytes(ByteBuffer block, int length) {
		final byte[] bytes = new byte[length];

		block.get(bytes);
		return bytes;
	}

	private static boolean isZero(byte[] bytes) {
		for (final byte value : bytes) {
			if (value != 0) {
				return false;
			}
		}

		return true;
	}

	private static byte[] reverse(byte[] bytes) {
		final byte[] result = new byte[bytes.length];

		for (int i = 0; i < bytes.length; i++) {
			result[i] = bytes[bytes.length - 1 - i];
		}

		return result;
	}

	public static String toDisplayString(byte[] hash) {
		return HexFormat.of().formatHex(reverse(hash));
	}

	public static byte[] fromDisplayString(String hash) {
		return reverse(HexFormat.of().parseHex(hash));
	}

	public static boolean isGenesis(byte[] hash) {
		return Arrays.equals(GENESIS_HASH, hash);
	}
}
