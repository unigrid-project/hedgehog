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

package org.unigrid.hedgehog.model.storage;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.crypto.MerkleTree;

public class FragmentTest {
	private static final int INDEX_OFFSET = GroupDescriptor.ENCODED_SIZE;

	private static Fragment anyFragment(Random random, GroupKey key, LayoutParameters layout) {
		return ChunkGroupsTest.seal(random, key, layout).get(random.nextInt(layout.maxFragments()));
	}

	private static byte[] randomBytes(Random random, int length) {
		final byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		return bytes;
	}

	/* Anything but a clean rejection or a verdict, such as an unexpected runtime exception, fails the property */
	private static boolean verifies(byte[] encoded, GroupId expected) {
		try {
			return Fragment.decode(encoded).verify(expected);
		} catch (IllegalArgumentException | BufferUnderflowException ex) {
			return false;
		}
	}

	private static boolean decodes(byte[] encoded) {
		try {
			Fragment.decode(encoded);
			return true;
		} catch (IllegalArgumentException | BufferUnderflowException ex) {
			return false;
		}
	}

	@Provide
	public Arbitrary<LayoutParameters> layouts() {
		return LayoutParametersTest.validLayouts();
	}

	@Property(tries = 50)
	public void roundTripsThroughBytes(@ForAll long seed, @ForAll("layouts") LayoutParameters layout) {
		final Random random = new Random(seed);
		final GroupKey key = ChunkGroupsTest.key(random);
		final Fragment fragment = anyFragment(random, key, layout);
		final Fragment decoded = Fragment.decode(fragment.encode());

		assertThat(decoded.encode(), equalTo(fragment.encode()));
		assertThat(decoded, equalTo(fragment));
		assertThat(decoded.hashCode(), equalTo(fragment.hashCode()));
		assertThat(decoded.verify(key.groupId()), is(true));
		assertThat(decoded.toString(), not(anyOf(containsString("data="), containsString("proof="))));
		assertThat(decoded.groupId(), equalTo(key.groupId()));
		assertThat(fragment.encode()[0], equalTo(StorageFormat.current().getId()));
		assertThat(fragment.getDescriptor().encode().length, equalTo(GroupDescriptor.ENCODED_SIZE));
	}

	@Property(tries = 200)
	public void detectsAnyChangedByte(@ForAll long seed, @ForAll("layouts") LayoutParameters layout,
		@ForAll @IntRange(min = 0, max = 100_000) int position, @ForAll @IntRange(min = 1, max = 0xFF) int mask) {

		final Random random = new Random(seed);
		final GroupKey key = ChunkGroupsTest.key(random);
		final byte[] encoded = anyFragment(random, key, layout).encode();

		encoded[position % encoded.length] ^= mask;
		assertThat(verifies(encoded, key.groupId()), is(false));
	}

	@Property(tries = 100)
	public void neverVerifiesAnotherIndex(@ForAll long seed, @ForAll("layouts") LayoutParameters layout,
		@ForAll byte index) {

		final Random random = new Random(seed);
		final GroupKey key = ChunkGroupsTest.key(random);
		final byte[] encoded = anyFragment(random, key, layout).encode();

		Assume.that(encoded[INDEX_OFFSET] != index);
		encoded[INDEX_OFFSET] = index;
		assertThat(Fragment.decode(encoded).verify(key.groupId()), is(false));
	}

	@Property(tries = 100)
	public void neverVerifiesAProofOfTheWrongLength(@ForAll long seed, @ForAll("layouts") LayoutParameters layout,
		@ForAll @IntRange(min = 0, max = 0xFF) int proofLength) {

		final Random random = new Random(seed);
		final GroupKey key = ChunkGroupsTest.key(random);
		final Fragment fragment = anyFragment(random, key, layout);

		Assume.that(proofLength != fragment.getProof().size());

		final List<byte[]> proof = IntStream.range(0, proofLength).mapToObj(i -> i < fragment.getProof().size()
			? fragment.getProof().get(i) : randomBytes(random, MerkleTree.HASH_SIZE)).collect(Collectors.toList());
		final Fragment forged = new Fragment(fragment.getDescriptor(), fragment.getIndex(), proof, fragment.getData());

		assertThat(Fragment.decode(forged.encode()).verify(key.groupId()), is(false));
	}

	@Property(tries = 100)
	public void rejectsMissingOrTrailingBytes(@ForAll long seed, @ForAll("layouts") LayoutParameters layout,
		@ForAll @IntRange(min = -1000, max = 1000) int change) {

		Assume.that(change != 0);

		final Random random = new Random(seed);
		final byte[] encoded = anyFragment(random, ChunkGroupsTest.key(random), layout).encode();
		final byte[] resized = Arrays.copyOf(encoded, Math.max(0, encoded.length + change));

		assertThat(decodes(resized), is(false));
	}

	@Property(tries = 200)
	public void survivesArbitraryCorruption(@ForAll long seed, @ForAll("layouts") LayoutParameters layout,
		@ForAll @IntRange(min = 1, max = 16) int edits, @ForAll @IntRange(min = -64, max = 64) int change) {

		final Random random = new Random(seed);
		final GroupKey key = ChunkGroupsTest.key(random);
		final byte[] original = anyFragment(random, key, layout).encode();
		final byte[] corrupted = Arrays.copyOf(original, Math.max(0, original.length + change));

		IntStream.range(0, Math.min(edits, corrupted.length)).forEach(i ->
			corrupted[random.nextInt(corrupted.length)] = (byte) random.nextInt());

		assertThat(verifies(corrupted, key.groupId()), is(Arrays.equals(corrupted, original)));
	}

	@Property
	public void survivesArbitraryBytes(@ForAll @Size(max = 4096) byte[] bytes, @ForAll boolean formatted,
		@ForAll long seed) {

		if (formatted && bytes.length > 0) {
			bytes[0] = StorageFormat.current().getId();
		}

		assertThat(verifies(bytes, ChunkGroupsTest.key(new Random(seed)).groupId()), is(false));
	}

	@Property
	public void verifiesNothingForged(@ForAll long seed, @ForAll byte dataFragments, @ForAll byte parityFragments,
		@ForAll byte maxFragments, @ForAll byte index, @ForAll @IntRange(min = 0, max = 12) int proofLength,
		@ForAll @IntRange(min = 0, max = 512) int fragmentSize) {

		final Random random = new Random(seed);
		final byte[] publicKey = randomBytes(random, GroupKey.PUBLIC_KEY_SIZE);
		final ByteBuffer forged = ByteBuffer.allocate(GroupDescriptor.ENCODED_SIZE + 2
			+ proofLength * MerkleTree.HASH_SIZE + Integer.BYTES + fragmentSize);

		forged.put(StorageFormat.current().getId()).put(dataFragments).put(parityFragments).put(maxFragments)
			.putInt(fragmentSize).put(publicKey).put(randomBytes(random, MerkleTree.HASH_SIZE))
			.put(randomBytes(random, GroupKey.SIGNATURE_SIZE)).put(index).put((byte) proofLength)
			.put(randomBytes(random, proofLength * MerkleTree.HASH_SIZE)).putInt(fragmentSize)
			.put(randomBytes(random, fragmentSize));

		assertThat(Fragment.decode(forged.array()).verify(GroupKey.groupIdOf(publicKey)), is(false));
	}

	@Property(tries = 50)
	public void refusesADescriptorOfAnUnknownFormat(@ForAll long seed, @ForAll("layouts") LayoutParameters layout,
		@ForAll byte format) {

		Assume.that(format != StorageFormat.current().getId());

		final Random random = new Random(seed);
		final byte[] encoded = anyFragment(random, ChunkGroupsTest.key(random), layout).encode();

		encoded[0] = format;
		assertThrows(IllegalArgumentException.class, () -> Fragment.decode(encoded));
	}
}
