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

package org.unigrid.hedgehog.model.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.storage.crypto.MerkleTree;

public class GroupDescriptorTest {
	private static final String CONTEXT = "hh-group-v1";

	private static GroupKey knownKey() {
		final byte[] seed = new byte[GroupKey.SEED_SIZE];

		for (int i = 0; i < seed.length; i++) {
			seed[i] = (byte) i;
		}

		return new GroupKey(seed);
	}

	private static byte[] knownRoot() {
		final byte[] root = new byte[MerkleTree.HASH_SIZE];
		Arrays.fill(root, (byte) 0x5A);
		return root;
	}

	private static boolean signs(GroupKey key, LayoutParameters layout) {
		try {
			GroupDescriptor.sign(key, StorageFormat.current(), layout, knownRoot());
			return true;
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static boolean accepts(LayoutParameters layout) {
		try {
			layout.validate();
			return true;
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}

	/* Arbitrary sizes almost never form a valid layout, so known-valid layouts make up half the samples */
	@Provide
	public Arbitrary<LayoutParameters> unvalidatedLayouts() {
		return Arbitraries.oneOf(Combinators.combine(Arbitraries.integers().between(-8, 1 << 16),
			Arbitraries.integers().between(-8, 512), Arbitraries.integers().between(-10, 300),
			Arbitraries.integers().between(-10, 30_000)
		).as((chunkSize, fragmentSize, innerParity, maxParity) -> LayoutParametersTest.small().toBuilder()
			.chunkSize(chunkSize).fragmentSize(fragmentSize).innerParityPercent(innerParity)
			.maxParityPercent(maxParity).build()
		), LayoutParametersTest.validLayouts());
	}

	@Example
	public void signsTheKnownAnswer() {
		final GroupDescriptor descriptor = GroupDescriptor.sign(knownKey(), StorageFormat.V1,
			LayoutParametersTest.small(), knownRoot());
		final byte[] signed = descriptor.signedBytes();

		assertThat(new String(signed, 0, CONTEXT.length(), StandardCharsets.US_ASCII), equalTo(CONTEXT));
		assertThat(HexFormat.of().formatHex(signed), equalTo("68682d67726f75702d763101080410000000"
			+ "8003a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
			+ "5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a"));
		assertThat(HexFormat.of().formatHex(descriptor.getSignature()), equalTo(
			"79dd83a4c16e7e909f3544eeb94e8c02dbf9d66a1a0b6cfcd4d06a0a33a06403"
			+ "373623686832e6be85460efe0d4909a435125b410976d7a8d7920700d41b3a0f"));
	}

	@Property(tries = 50)
	public void roundTripsThroughBytes(@ForAll long seed, @ForAll("layouts") LayoutParameters layout) {
		final Random random = new Random(seed);
		final byte[] root = new byte[MerkleTree.HASH_SIZE];
		random.nextBytes(root);

		final GroupDescriptor descriptor = GroupDescriptor.sign(ChunkGroupsTest.key(random), StorageFormat.current(),
			layout, root);
		final byte[] encoded = descriptor.encode();

		assertThat(encoded.length, equalTo(GroupDescriptor.ENCODED_SIZE));
		assertThat(GroupDescriptor.decode(ByteBuffer.wrap(encoded)), equalTo(descriptor));
		assertThat(descriptor.isValid() && descriptor.isWellFormed(), is(true));
	}

	@Provide
	public Arbitrary<LayoutParameters> layouts() {
		return LayoutParametersTest.validLayouts();
	}

	@Property
	public void signsExactlyTheValidLayoutsWithoutTruncation(@ForAll("unvalidatedLayouts") LayoutParameters layout) {
		Statistics.label("accepted").collect(accepts(layout)).coverage(checker -> checker.check(true).count(n -> n > 0));
		assertThat(signs(knownKey(), layout), is(accepts(layout)));

		if (accepts(layout)) {
			final GroupDescriptor decoded = GroupDescriptor.decode(ByteBuffer.wrap(GroupDescriptor.sign(knownKey(),
				StorageFormat.current(), layout, knownRoot()).encode()));

			assertThat(decoded.getDataFragments(), equalTo(layout.dataFragments()));
			assertThat(decoded.getParityFragments(), equalTo(layout.parityFragments()));
			assertThat(decoded.getMaxFragments(), equalTo(layout.maxFragments()));
			assertThat(decoded.getFragmentSize(), equalTo(layout.getFragmentSize()));
		}
	}
}
