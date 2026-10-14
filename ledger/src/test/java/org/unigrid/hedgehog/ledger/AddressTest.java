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
import java.util.List;
import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class AddressTest {
	private static byte[] filled(int size, int fill) {
		final byte[] bytes = new byte[size];

		Arrays.fill(bytes, (byte) fill);
		return bytes;
	}

	private static Address randomAddress(Random random) {
		final byte[] key = CodecFixtures.random(random, AccountKey.SIZE);
		final byte[] hash = CodecFixtures.random(random, LegacyAddress.SIZE);

		return random.nextBoolean() ? new AccountKey(key) : new LegacyAddress(hash);
	}

	@Property(tries = 100)
	public void shouldRoundTripEitherKind(@ForAll long seed) {
		final Address address = randomAddress(new Random(seed));
		final ByteBuffer in = ByteBuffer.wrap(address.encode());

		assertThat(Address.decode(in), equalTo(address));
		assertThat(in.hasRemaining(), is(false));
		assertThat(address.encode().length, equalTo(1 + address.bytes().length));
		assertThat(address.encode()[0], equalTo(address.type()));
	}

	@Property(tries = 100)
	public void shouldRefuseEveryTruncation(@ForAll long seed, @ForAll @IntRange(min = 0, max = 1000) int cutSeed) {
		final byte[] encoded = randomAddress(new Random(seed)).encode();
		final ByteBuffer in = ByteBuffer.wrap(encoded, 0, cutSeed % encoded.length);

		assertThrows(IllegalArgumentException.class, () -> Address.decode(in));
	}

	@Example
	public void shouldRefuseAnUnknownType() {
		for (final byte type : new byte[] { 0, 3, 9, -1 }) {
			final byte[] encoded = new AccountKey(filled(AccountKey.SIZE, 1)).encode();

			encoded[0] = type;
			assertThrows(IllegalArgumentException.class, () -> Address.decode(ByteBuffer.wrap(encoded)));
		}
	}

	/* The type is part of who an address is, so the same leading bytes under the two kinds are two addresses */
	@Example
	public void shouldNeverTakeALegacyAddressForAKey() {
		final Address key = new AccountKey(filled(AccountKey.SIZE, 7));
		final Address legacy = new LegacyAddress(filled(LegacyAddress.SIZE, 7));

		assertThat(key, not(equalTo(legacy)));
		assertThat(legacy, not(equalTo(key)));
		assertThat(key.encode(), not(equalTo(legacy.encode())));
		assertThat(key.type(), equalTo(Address.ED25519));
		assertThat(legacy.type(), equalTo(Address.LEGACY));
	}

	@Example
	public void shouldOrderByTypeThenByUnsignedBytes() {
		final Address lowKey = new AccountKey(filled(AccountKey.SIZE, 1));
		final Address highKey = new AccountKey(filled(AccountKey.SIZE, -1));
		final Address lowLegacy = new LegacyAddress(filled(LegacyAddress.SIZE, 1));

		assertThat(lowKey.compareTo(highKey), lessThan(0));
		assertThat(highKey.compareTo(lowLegacy), lessThan(0));
		assertThat(lowLegacy.compareTo(lowKey) > 0, is(true));
		assertThat(List.of(lowLegacy, highKey, lowKey).stream().sorted().toList(),
			equalTo(List.of(lowKey, highKey, lowLegacy)));
	}

	@Example
	public void shouldRefuseALegacyAddressOfTheWrongSize() {
		assertThrows(IllegalArgumentException.class, () -> new LegacyAddress(new byte[19]));
		assertThrows(IllegalArgumentException.class, () -> new LegacyAddress(new byte[32]));
	}

	@Example
	public void shouldCompareLegacyAddressesByContentAndKeepTheirOwnCopy() {
		final byte[] hash = filled(LegacyAddress.SIZE, 5);
		final LegacyAddress one = new LegacyAddress(hash);

		hash[0] ^= 1;
		assertThat(one, equalTo(new LegacyAddress(filled(LegacyAddress.SIZE, 5))));
		assertThat(one.hashCode(), equalTo(new LegacyAddress(filled(LegacyAddress.SIZE, 5)).hashCode()));
		one.bytes()[0] ^= 1;
		assertThat(one, equalTo(new LegacyAddress(filled(LegacyAddress.SIZE, 5))));
		assertThat(one.toString(), equalTo("05".repeat(LegacyAddress.SIZE)));
	}

	@Example
	public void shouldNameAnUnknownTypeByteUnsigned() {
		final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
			() -> Address.decode(ByteBuffer.wrap(new byte[] { (byte) 200, 0, 0 })));

		assertThat(thrown.getMessage(), containsString("200"));
	}
}
