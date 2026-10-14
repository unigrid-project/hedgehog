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

	private static int certificateSize(int endorsements) {
		return Integer.BYTES + Short.BYTES + endorsements * ENDORSEMENT_SIZE;
	}

	private static int transactionCountOffset(int endorsements) {
		return Block.HEADER_SIZE + certificateSize(endorsements)
			+ certificateSize(CodecFixtures.LAST_COMMIT_ENDORSEMENTS);
	}

	private static int lastCommitOffset(int endorsements) {
		return Block.HEADER_SIZE + certificateSize(endorsements);
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

		ByteBuffer.wrap(encoded).putShort(Block.HEADER_SIZE + Integer.BYTES, (short) (ValidatorSet.MAX_SIZE + 1));
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(encoded));
		ByteBuffer.wrap(encoded).putShort(Block.HEADER_SIZE + Integer.BYTES, (short) 0xffff);
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(encoded));
	}

	@Example
	public void shouldRoundTripABlockWithAFullLastCommit() {
		final Block block = CodecFixtures.block(new Random(5), 3, ValidatorSet.MAX_SIZE);
		final Block full = block.toBuilder().lastCommit(block.getCertificate()).build();

		assertThat(BlockCodec.decode(BlockCodec.encode(full)), equalTo(full));
		assertThat(BlockCodec.encode(full).length <= BlockCodec.MAX_ENCODED_SIZE, equalTo(true));
	}

	/* MAX_ENCODED_SIZE is made of the larger transaction kind, so no mint may be larger than a vote */
	@Example
	public void shouldNotLetAMintBeLargerThanAVote() {
		assertThat(Mint.MAX_ENCODED_SIZE <= Vote.ENCODED_SIZE, equalTo(true));
	}

	@Example
	public void shouldRefuseALastCommitCountAboveTheMaximum() {
		final byte[] encoded = BlockCodec.encode(CodecFixtures.block(new Random(3), 1, 2));

		ByteBuffer.wrap(encoded).putShort(lastCommitOffset(2) + Integer.BYTES, (short) (ValidatorSet.MAX_SIZE + 1));
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(encoded));
	}

	/* The header names the last commit by its hash, and a header that names another is not a block */
	@Example
	public void shouldRefuseAHeaderThatNamesAnotherLastCommit() {
		final byte[] encoded = BlockCodec.encode(CodecFixtures.block(new Random(3), 1, 2));
		final int hashOffset = Block.HEADER_SIZE - AccountKey.SIZE - Digests.HASH_SIZE;

		encoded[hashOffset] ^= 1;
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(encoded));
	}

	@Example
	public void shouldRefuseANegativeCertificateView() {
		final byte[] encoded = BlockCodec.encode(CodecFixtures.block(new Random(3), 1, 2));

		ByteBuffer.wrap(encoded).putInt(Block.HEADER_SIZE, -1);
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(encoded));
	}

	@Example
	public void shouldRefuseANegativeBlockView() {
		final byte[] encoded = BlockCodec.encode(CodecFixtures.block(new Random(3), 1, 2));

		ByteBuffer.wrap(encoded).putInt(Long.BYTES, -1);
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(encoded));
	}

	/* What could not be decoded again must not be written, or a log would hold a record that cannot be read */
	@Example
	public void shouldRefuseToEncodeMoreThanTheMaximum() {
		final Block tooManyTransactions = CodecFixtures.block(new Random(3), Block.MAX_TRANSACTIONS + 1, 2);
		final Block tooManyEndorsements = CodecFixtures.block(new Random(3), 1, ValidatorSet.MAX_SIZE + 1);

		assertThrows(IllegalArgumentException.class, () -> BlockCodec.encode(tooManyTransactions));
		assertThrows(IllegalArgumentException.class, () -> BlockCodec.encode(tooManyEndorsements));
	}

	@Example
	public void shouldRefuseCountsLargerThanTheBytesThatFollow() {
		final byte[] encoded = BlockCodec.encode(CodecFixtures.block(new Random(3), 1, 2));
		final byte[] moreTransactions = encoded.clone();
		final byte[] moreEndorsements = encoded.clone();

		ByteBuffer.wrap(moreTransactions).putShort(transactionCountOffset(2), (short) 5);
		ByteBuffer.wrap(moreEndorsements).putShort(Block.HEADER_SIZE + Integer.BYTES, (short) 9);
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
