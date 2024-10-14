/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.service.storage;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import lombok.SneakyThrows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.StorageStatus;
import org.unigrid.hedgehog.model.storage.TestClock;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;

public class FragmentKeeperTest {
	@SneakyThrows
	private static FragmentStore store() {
		return new FragmentStore(Jimfs.newFileSystem(Configuration.unix()).getPath("/fragments"), new TestClock());
	}

	private static FragmentKeeper keeper(final FragmentStore store) {
		return new FragmentKeeper(store, () -> Optional.of(StorageTestData.parameters()));
	}

	@Property(tries = 50)
	public void storesAndReturnsAnyValidFragment(@ForAll long seed, @ForAll @IntRange(min = 0, max = 15) int index) {
		final Random random = new Random(seed);
		final GroupKey key = StorageTestData.key(random);
		final FragmentStore store = store();
		final FragmentKeeper keeper = keeper(store);
		final Fragment fragment = StorageTestData.group(key, random).get(index);

		assertThat(keeper.store(fragment.encode()), equalTo(StorageStatus.OK));
		assertThat(keeper.fetch(key.groupId()).get(), equalTo(fragment.encode()));
		assertThat(keeper.census(List.of(key.groupId())).get(0),
			equalTo(new FragmentStatus.Entry(key.groupId(), FragmentStatus.State.HELD, index)));
		assertThat(store.holding(key.groupId()).get().getTier(),
			equalTo(fragment.isExtra() ? FragmentStore.Tier.EXTRA : FragmentStore.Tier.GUARANTEED));
		assertThat(store.holding(key.groupId()).get().getSlots(), equalTo(fragment.getDescriptor().getMaxFragments()));
	}

	@Property(tries = 200)
	public void rejectsAnySingleBitFlip(@ForAll long seed, @ForAll @IntRange(min = 0, max = 100_000) int position,
		@ForAll @IntRange(min = 0, max = 7) int bit) {

		final Random random = new Random(seed);
		final byte[] tampered = StorageTestData.group(StorageTestData.key(random), random).get(0).encode();
		tampered[position % tampered.length] ^= (byte) (1 << bit);

		assertThat(keeper(store()).store(tampered), equalTo(StorageStatus.INVALID));
	}

	@Property(tries = 100)
	public void rejectsArbitraryBytes(@ForAll @Size(max = 2048) byte[] garbage) {
		assertThat(keeper(store()).store(garbage), equalTo(StorageStatus.INVALID));
	}

	@Property(tries = 20)
	public void refusesEverythingWithoutASpork(@ForAll long seed) {
		final Random random = new Random(seed);
		final GroupKey key = StorageTestData.key(random);
		final FragmentKeeper keeper = new FragmentKeeper(store(), Optional::empty);

		assertThat(keeper.store(StorageTestData.group(key, random).get(0).encode()), equalTo(StorageStatus.DISABLED));
		assertThat(keeper.delete(key.groupId(), 1, key.signDelete(1)), equalTo(StorageStatus.DISABLED));
	}

	@Property(tries = 50)
	public void holdsOneFragmentPerGroup(@ForAll long seed, @ForAll @IntRange(min = 0, max = 15) int first,
		@ForAll @IntRange(min = 0, max = 15) int second) {

		final Random random = new Random(seed);
		final List<Fragment> fragments = StorageTestData.group(StorageTestData.key(random), random);
		final FragmentKeeper keeper = keeper(store());

		keeper.store(fragments.get(first).encode());
		assertThat(keeper.store(fragments.get(second).encode()), equalTo(StorageStatus.DUPLICATE));
	}

	@Property(tries = 50)
	public void deletesOnlyWithTheGroupKey(@ForAll long seed, @ForAll long timestamp) {
		final Random random = new Random(seed);
		final GroupKey key = StorageTestData.key(random);
		final GroupKey stranger = StorageTestData.key(random);
		final Fragment fragment = StorageTestData.group(key, random).get(0);
		final FragmentKeeper keeper = keeper(store());

		keeper.store(fragment.encode());
		assertThat(keeper.delete(key.groupId(), timestamp, stranger.signDelete(timestamp)),
			equalTo(StorageStatus.INVALID));
		assertThat(keeper.delete(key.groupId(), timestamp, key.signDelete(timestamp + 1)),
			equalTo(StorageStatus.INVALID));
		assertThat(keeper.delete(key.groupId(), timestamp, key.signDelete(timestamp)), equalTo(StorageStatus.OK));
		assertThat(keeper.census(List.of(key.groupId())).get(0).getState(), equalTo(FragmentStatus.State.TOMBSTONE));
		assertThat(keeper.store(fragment.encode()), equalTo(StorageStatus.TOMBSTONE));
	}
}
