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
import java.util.Arrays;
import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class BlockCodecTest {
	private static final int ENDORSEMENT_SIZE = AccountKey.SIZE + Ed25519.SIGNATURE_SIZE;

	private static int transactionCountOffset(int endorsements) {
		return Block.HEADER_SIZE + Short.BYTES + endorsements * ENDORSEMENT_SIZE;
	}

	@Property(tries = 100)
	public void shouldRoundTripABlock(@ForAll long seed, @ForAll @IntRange(min = 0, max = 20) int transactions,
		@ForAll @IntRange(min = 0, max = 8) int endorsements) {
		final Block block = CodecFixtures.block(new Random(seed), transactions, endorsements);

		assertThat(BlockCodec.decode(BlockCodec.encode(block)), equalTo(block));
	}

	@Example
	public void shouldRoundTripTheLargestBlock() {
		final Block block = CodecFixtures.block(new Random(7), Block.MAX_TRANSACTIONS, ValidatorSet.MAX_SIZE);

		assertThat(BlockCodec.decode(BlockCodec.encode(block)), equalTo(block));
	}

	@Property(tries = 300)
	public void shouldRefuseEveryTruncation(@ForAll long seed, @ForAll @IntRange(min = 0, max = 100000) int cutSeed) {
		final byte[] encoded = BlockCodec.encode(CodecFixtures.block(new Random(seed), 3, 3));
		final int cut = cutSeed % encoded.length;

		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(Arrays.copyOf(encoded, cut)));
	}

	@Property(tries = 50)
	public void shouldRefuseTrailingBytes(@ForAll long seed, @ForAll @IntRange(min = 1, max = 8) int extra) {
		final byte[] encoded = BlockCodec.encode(CodecFixtures.block(new Random(seed), 2, 2));

		assertThrows(IllegalArgumentException.class,
			() -> BlockCodec.decode(Arrays.copyOf(encoded, encoded.length + extra)));
	}

	/* A forged count must fail before anything is allocated for it */
	@Example
	public void shouldRefuseATransactionCountAboveTheMaximum() {
		final byte[] encoded = BlockCodec.encode(CodecFixtures.block(new Random(3), 1, 2));
		final int offset = transactionCountOffset(2);

		ByteBuffer.wrap(encoded).putShort(offset, (short) (Block.MAX_TRANSACTIONS + 1));
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(encoded));
		ByteBuffer.wrap(encoded).putShort(offset, (short) 0xffff);
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(encoded));
	}

	@Example
	public void shouldRefuseAnEndorsementCountAboveTheMaximum() {
		final byte[] encoded = BlockCodec.encode(CodecFixtures.block(new Random(3), 1, 2));

		ByteBuffer.wrap(encoded).putShort(Block.HEADER_SIZE, (short) (ValidatorSet.MAX_SIZE + 1));
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(encoded));
		ByteBuffer.wrap(encoded).putShort(Block.HEADER_SIZE, (short) 0xffff);
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(encoded));
	}

	@Example
	public void shouldRefuseCountsLargerThanTheBytesThatFollow() {
		final byte[] encoded = BlockCodec.encode(CodecFixtures.block(new Random(3), 1, 2));
		final byte[] moreTransactions = encoded.clone();
		final byte[] moreEndorsements = encoded.clone();

		ByteBuffer.wrap(moreTransactions).putShort(transactionCountOffset(2), (short) 5);
		ByteBuffer.wrap(moreEndorsements).putShort(Block.HEADER_SIZE, (short) 9);
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(moreTransactions));
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(moreEndorsements));
	}

	@Example
	public void shouldRefuseAnUnknownTransactionKind() {
		final byte[] encoded = BlockCodec.encode(CodecFixtures.block(new Random(3), 1, 2));

		encoded[transactionCountOffset(2) + Short.BYTES] = 9;
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(encoded));
	}

	@Example
	public void shouldEncodeToTheSameBytesEveryTime() {
		final Block block = CodecFixtures.block(new Random(11), 4, 3);

		assertThat(BlockCodec.encode(block), equalTo(BlockCodec.encode(block)));
	}
}
