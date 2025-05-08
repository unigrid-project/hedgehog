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
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class TransactionCodecTest {
	@Property(tries = 200)
	public void shouldRoundTripAnyTransaction(@ForAll long seed) {
		final Transaction transaction = CodecFixtures.transaction(new Random(seed));
		final ByteBuffer in = ByteBuffer.wrap(transaction.encode());

		assertThat(TransactionCodec.decode(in), equalTo(transaction));
		assertThat(in.hasRemaining(), is(false));
	}

	@Property(tries = 300)
	public void shouldRefuseEveryTruncation(@ForAll long seed, @ForAll @IntRange(min = 0, max = 10000) int cutSeed) {
		final byte[] encoded = CodecFixtures.transaction(new Random(seed)).encode();
		final ByteBuffer in = ByteBuffer.wrap(encoded, 0, cutSeed % encoded.length);

		assertThrows(IllegalArgumentException.class, () -> TransactionCodec.decode(in));
	}

	@Example
	public void shouldRefuseAnUnknownKind() {
		final byte[] encoded = CodecFixtures.mint(new Random(1)).encode();

		encoded[0] = 9;
		assertThrows(IllegalArgumentException.class, () -> TransactionCodec.decode(ByteBuffer.wrap(encoded)));
	}

	@Example
	public void shouldRefuseAVoteWithAnUnknownAction() {
		final byte[] encoded = CodecFixtures.vote(new Random(1)).encode();

		encoded[1 + 2 * AccountKey.SIZE] = 9;
		assertThrows(IllegalArgumentException.class, () -> TransactionCodec.decode(ByteBuffer.wrap(encoded)));
	}

	@Example
	public void shouldRefuseAMintOfNothing() {
		final byte[] encoded = CodecFixtures.mint(new Random(1)).encode();

		ByteBuffer.wrap(encoded).putLong(1 + AccountKey.SIZE, 0);
		assertThrows(IllegalArgumentException.class, () -> TransactionCodec.decode(ByteBuffer.wrap(encoded)));
	}

	@Example
	public void shouldLeaveTheBytesAfterATransactionAlone() {
		final byte[] encoded = CodecFixtures.mint(new Random(1)).encode();
		final ByteBuffer in = ByteBuffer.wrap(Arrays.copyOf(encoded, encoded.length + 3));

		TransactionCodec.decode(in);
		assertThat(in.remaining(), equalTo(3));
	}
}
