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

package org.unigrid.hedgehog.s3.index;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.OptionalLong;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Positive;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class KeysTest {
	private record Address(String bucket, String key, long sequence) {
	}

	private static final Comparator<String> UTF8 = Comparator.comparing(text -> text.getBytes(StandardCharsets.UTF_8),
		Arrays::compareUnsigned);

	private static final Comparator<Address> S3_ORDER = Comparator.comparing(Address::bucket, UTF8)
		.thenComparing(Address::key, UTF8).thenComparing(Address::sequence, Comparator.reverseOrder());

	@Provide
	Arbitrary<String> texts() {
		return IndexArbitraries.texts();
	}

	@Provide
	Arbitrary<Address> addresses() {
		return Combinators.combine(texts(), texts(), Arbitraries.longs().between(1, Long.MAX_VALUE))
			.as(Address::new);
	}

	private static byte[] encode(final Address address) {
		return Keys.version(address.bucket(), address.key(), address.sequence());
	}

	@Property
	public void comparesTextInUtf8Order(@ForAll("texts") final String first,
		@ForAll("texts") final String second) {

		assertThat(Integer.signum(Keys.compare(first, second)),
			equalTo(Integer.signum(UTF8.compare(first, second))));
	}

	@Property
	public void ordersVersionsByBucketKeyAndNewestFirst(@ForAll("addresses") final Address first,
		@ForAll("addresses") final Address second) {

		assertThat(Integer.signum(Arrays.compareUnsigned(encode(first), encode(second))),
			equalTo(Integer.signum(S3_ORDER.compare(first, second))));
	}

	@Property
	public void ordersUploadsOldestFirst(@ForAll("addresses") final Address address,
		@ForAll @Positive final long later) {

		final long next = address.sequence() + later;

		Assume.that(next > 0);
		assertThat(Arrays.compareUnsigned(Keys.upload(address.bucket(), address.key(), address.sequence()),
			Keys.upload(address.bucket(), address.key(), next)) < 0, is(true));
	}

	@Property
	public void parsesTheKeysItBuilds(@ForAll("addresses") final Address address) throws CorruptIndexException {
		assertThat(Keys.parse(encode(address)), equalTo(new Keys.Parsed(address.bucket(), address.key(),
			address.sequence())));

		assertThat(Keys.parse(Keys.upload(address.bucket(), address.key(), address.sequence())),
			equalTo(new Keys.Parsed(address.bucket(), address.key(), address.sequence())));
	}

	@Property
	public void keepsExactlyTheKeysWithAPrefixInsideItsRange(@ForAll("addresses") final Address address,
		@ForAll("texts") final String prefix) {

		final byte[] start = Keys.objects(Keys.VERSION, address.bucket(), prefix);
		final byte[] end = Keys.end(start);
		final byte[] key = encode(address);
		final boolean inside = Arrays.compareUnsigned(key, start) >= 0
			&& (Objects.isNull(end) || Arrays.compareUnsigned(key, end) < 0);

		assertThat(inside, equalTo(address.key().startsWith(prefix)));
	}

	@Property
	public void keepsEveryVersionOfAKeyInsideItsRange(@ForAll("addresses") final Address address,
		@ForAll("addresses") final Address other) {

		final byte[] start = Keys.object(Keys.VERSION, address.bucket(), address.key());
		final byte[] key = encode(other);
		final boolean inside = Arrays.compareUnsigned(key, start) >= 0
			&& Arrays.compareUnsigned(key, Keys.end(start)) < 0;

		assertThat(inside, equalTo(address.bucket().equals(other.bucket()) && address.key().equals(other.key())));
	}

	@Property
	public void readsBackTheIdsItHandsOut(@ForAll @Positive final long sequence) {
		assertThat(Keys.id(sequence).length(), equalTo(16));
		assertThat(Keys.sequence(Keys.id(sequence)), equalTo(OptionalLong.of(sequence)));
	}

	@Example
	public void refusesIdsItCouldNotHaveHandedOut() {
		for (String id : new String[] {"null", "", "00000000000000AB", "000000000000000g", "00000000000000001"}) {
			assertThat(Keys.sequence(id), equalTo(OptionalLong.empty()));
		}
	}
}
