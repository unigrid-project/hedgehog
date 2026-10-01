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

import java.util.ArrayList;
import java.util.Collections;
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
import net.jqwik.api.statistics.Statistics;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.crypto.MerkleTree;

public class ChunkGroupsTest {
	static byte[] chunk(Random random, LayoutParameters layout) {
		final byte[] chunk = new byte[layout.getChunkSize()];
		random.nextBytes(chunk);
		return chunk;
	}

	static GroupKey key(Random random) {
		final byte[] seed = new byte[GroupKey.SEED_SIZE];
		random.nextBytes(seed);
		return new GroupKey(seed);
	}

	static List<Fragment> seal(Random random, GroupKey key, LayoutParameters layout) {
		return ChunkGroups.seal(chunk(random, layout), key, StorageFormat.current(), layout);
	}

	static List<Fragment> shuffled(List<Fragment> fragments, Random random) {
		final List<Fragment> shuffled = new ArrayList<>(fragments);
		Collections.shuffle(shuffled, random);
		return shuffled;
	}

	private static void covers(String label, boolean malformation) {
		Statistics.label(label).collect(malformation).coverage(checker -> checker.check(true).count(n -> n > 0));
	}

	@Provide
	public Arbitrary<LayoutParameters> layouts() {
		return LayoutParametersTest.validLayouts();
	}

	@Property(tries = 50)
	public void sealsVerifiableFragments(@ForAll long seed, @ForAll("layouts") LayoutParameters layout) {
		final Random random = new Random(seed);
		final GroupKey key = key(random);
		final List<Fragment> fragments = seal(random, key, layout);

		assertThat(fragments, hasSize(layout.maxFragments()));

		for (int i = 0; i < fragments.size(); i++) {
			final Fragment fragment = fragments.get(i);

			assertThat(fragment.getIndex(), equalTo(i));
			assertThat(fragment.verify(key.groupId()), is(true));
			assertThat(fragment.verify(key(random).groupId()), is(false));
			assertThat(fragment.isExtra(), is(i >= layout.guaranteedFragments()));
			assertThat(fragment.format(), equalTo(StorageFormat.current()));
		}
	}

	@Property(tries = 50)
	public void opensFromAnyDataFragmentsWorth(@ForAll long seed, @ForAll("layouts") LayoutParameters layout) {
		final Random random = new Random(seed);
		final byte[] chunk = chunk(random, layout);
		final List<Fragment> fragments = shuffled(ChunkGroups.seal(chunk, key(random), StorageFormat.current(),
			layout), random);

		assertThat(ChunkGroups.open(fragments.subList(0, layout.dataFragments())), equalTo(chunk));
	}

	@Property(tries = 50)
	public void rebuildsIdenticalFragments(@ForAll long seed, @ForAll("layouts") LayoutParameters layout) {
		final Random random = new Random(seed);
		final List<Fragment> fragments = seal(random, key(random), layout);
		final List<Fragment> sources = shuffled(fragments, random).subList(0, layout.dataFragments());
		final List<Integer> missing = IntStream.range(0, layout.maxFragments()).filter(i -> random.nextBoolean())
			.boxed().collect(Collectors.toList());
		final List<Fragment> rebuilt = ChunkGroups.rebuild(sources, missing);

		assertThat(rebuilt.stream().map(Fragment::encode).toArray(byte[][]::new),
			equalTo(missing.stream().map(i -> fragments.get(i).encode()).toArray(byte[][]::new)));
	}

	@Property(tries = 50)
	public void refusesTooFewFragments(@ForAll long seed, @ForAll("layouts") LayoutParameters layout) {
		final Random random = new Random(seed);
		final List<Fragment> sources = shuffled(seal(random, key(random), layout), random)
			.subList(0, layout.dataFragments() - 1);

		assertThrows(IllegalArgumentException.class, () -> ChunkGroups.open(sources));
		assertThrows(IllegalArgumentException.class, () -> ChunkGroups.rebuild(sources, List.of(0)));
	}

	@Property(tries = 50)
	public void refusesToRebuildFromAlteredData(@ForAll long seed, @ForAll("layouts") LayoutParameters layout) {
		final Random random = new Random(seed);
		final List<Fragment> sources = new ArrayList<>(shuffled(seal(random, key(random), layout), random)
			.subList(0, layout.dataFragments()));
		final int victim = random.nextInt(sources.size());
		final Fragment original = sources.get(victim);
		final byte[] altered = original.getData().clone();

		altered[random.nextInt(altered.length)] ^= 1 + random.nextInt(0xFF);
		sources.set(victim, new Fragment(original.getDescriptor(), original.getIndex(), original.getProof(), altered));
		assertThrows(IllegalStateException.class, () -> ChunkGroups.rebuild(sources, List.of(0)));
	}

	@Property(tries = 50)
	public void refusesFragmentsOfDifferentSeals(@ForAll long seed, @ForAll("layouts") LayoutParameters layout) {
		final Random random = new Random(seed);
		final GroupKey key = key(random);
		final List<Fragment> sources = new ArrayList<>(shuffled(seal(random, key, layout), random)
			.subList(0, layout.dataFragments()));

		final Fragment stranger = seal(random, key, layout).get(random.nextInt(layout.maxFragments()));

		sources.add(random.nextInt(sources.size() + 1), stranger);
		assertThrows(IllegalArgumentException.class, () -> ChunkGroups.open(sources));
		assertThrows(IllegalArgumentException.class, () -> ChunkGroups.rebuild(sources, List.of(0)));
	}

	@Property(tries = 50)
	public void refusesIndicesOutsideTheGroup(@ForAll long seed, @ForAll("layouts") LayoutParameters layout,
		@ForAll int index) {

		Assume.that(index < 0 || index >= layout.maxFragments());

		final Random random = new Random(seed);
		final List<Fragment> sources = new ArrayList<>(seal(random, key(random), layout));
		final Fragment original = sources.get(0);

		assertThrows(IllegalArgumentException.class, () -> ChunkGroups.rebuild(sources, List.of(0, index)));
		sources.add(new Fragment(original.getDescriptor(), index, original.getProof(), original.getData()));
		assertThrows(IllegalArgumentException.class, () -> ChunkGroups.open(sources));
		assertThrows(IllegalArgumentException.class, () -> ChunkGroups.rebuild(sources, List.of(0)));
	}

	@Property
	public void refusesValidlySignedMalformedGroups(@ForAll long seed,
		@ForAll @IntRange(min = 0, max = 0xFF) int dataFragments, @ForAll @IntRange(min = 0, max = 0xFF) int parity,
		@ForAll @IntRange(min = 0, max = 0xFF) int maxFragments, @ForAll @IntRange(min = -8, max = 64) int fragmentSize) {

		final Random random = new Random(seed);
		final GroupKey key = key(random);
		final GroupDescriptor unsigned = GroupDescriptor.builder().format(StorageFormat.current())
			.dataFragments(dataFragments).parityFragments(parity).maxFragments(maxFragments).fragmentSize(fragmentSize)
			.publicKey(key.publicKey()).merkleRoot(new byte[MerkleTree.HASH_SIZE])
			.signature(new byte[GroupKey.SIGNATURE_SIZE]).build();

		Assume.that(!unsigned.isWellFormed());
		covers("no data fragments", dataFragments == 0);
		covers("too few slots", maxFragments < dataFragments + parity);
		covers("no fragment size", fragmentSize <= 0);

		final GroupDescriptor signed = unsigned.toBuilder().signature(key.sign(unsigned.signedBytes())).build();
		final List<Fragment> fragments = IntStream.range(0, Math.max(1, Math.max(dataFragments, maxFragments)))
			.mapToObj(i -> new Fragment(signed, i, List.of(), new byte[Math.max(0, fragmentSize)]))
			.collect(Collectors.toList());

		assertThat(signed.isValid(), is(true));
		fragments.forEach(fragment -> assertThat(fragment.verify(key.groupId()), is(false)));
		assertThrows(IllegalArgumentException.class, () -> ChunkGroups.open(fragments));
		assertThrows(IllegalArgumentException.class, () -> ChunkGroups.rebuild(fragments, List.of(0)));
	}

	@Property(tries = 50)
	public void refusesChunksOfTheWrongSize(@ForAll long seed, @ForAll("layouts") LayoutParameters layout,
		@ForAll @IntRange(min = 0, max = 1 << 14) int size) {

		Assume.that(size != layout.getChunkSize());
		assertThrows(IllegalArgumentException.class, () -> ChunkGroups.seal(new byte[size], key(new Random(seed)),
			StorageFormat.current(), layout));
	}
}
