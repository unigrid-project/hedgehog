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

package org.unigrid.hedgehog.model.storage.store;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.TestClock;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.PutResult;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.Tier;

public class FragmentStoreTest {
	private static final StorageFormat FORMAT = StorageFormat.current();
	private static final int SLOTS = 32;
	private final Random random = new Random(1);

	private static Path root() {
		return Jimfs.newFileSystem(Configuration.unix()).getPath("/data/fragments");
	}

	private GroupId group() {
		final byte[] id = new byte[GroupId.SIZE];
		random.nextBytes(id);
		return GroupId.of(id);
	}

	@SneakyThrows
	private static FragmentStore store(Path root, TestClock clock, long maxBytes, int extraPoolPercent) {
		final FragmentStore store = new FragmentStore(root, clock);
		store.limits(maxBytes, extraPoolPercent);
		return store;
	}

	@SneakyThrows
	@Property(tries = 50)
	public void storesAndReturnsFragments(@ForAll @Size(max = 200) byte[] payload,
		@ForAll @IntRange(min = 0, max = 255) int index, @ForAll @IntRange(min = 1, max = 255) int slots,
		@ForAll Tier tier) {

		final FragmentStore store = store(root(), new TestClock(), 1000, 50);
		final GroupId id = group();

		assertThat(store.put(id, FORMAT, slots, tier, index, payload), equalTo(PutResult.STORED));
		assertThat(store.get(id).get(), equalTo(payload));
		assertThat(store.holding(id).get().getIndex(), equalTo(index));
		assertThat(store.holding(id).get().getSlots(), equalTo(slots));
		assertThat(store.holding(id).get().getTier(), equalTo(tier));
		assertThat(store.holding(id).get().getFormat(), equalTo(FORMAT));
		assertThat(store.put(id, FORMAT, slots, tier, index, new byte[] { 4 }), equalTo(PutResult.DUPLICATE));
	}

	@SneakyThrows
	@Example
	public void refusesGuaranteedFragmentsBeyondTheQuota() {
		final FragmentStore store = store(root(), new TestClock(), 10, 50);

		assertThat(store.put(group(), FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[8]), equalTo(PutResult.STORED));
		assertThat(store.put(group(), FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[8]), equalTo(PutResult.QUOTA));
	}

	@SneakyThrows
	@Example
	public void evictsTheOldestExtraForGuaranteedFragments() {
		final FragmentStore store = store(root(), new TestClock(), 12, 100);
		final GroupId oldest = group();
		final GroupId newest = group();

		store.put(oldest, FORMAT, SLOTS, Tier.EXTRA, 30, new byte[4]);
		store.put(newest, FORMAT, SLOTS, Tier.EXTRA, 31, new byte[4]);

		assertThat(store.put(group(), FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[8]), equalTo(PutResult.STORED));
		assertThat(store.holding(oldest).isPresent(), is(false));
		assertThat(store.holding(newest).isPresent(), is(true));
	}

	@SneakyThrows
	@Example
	public void rollsExtrasWithinTheirPool() {
		final FragmentStore store = store(root(), new TestClock(), 100, 10);
		final GroupId first = group();

		store.put(first, FORMAT, SLOTS, Tier.EXTRA, 20, new byte[10]);
		assertThat(store.put(group(), FORMAT, SLOTS, Tier.EXTRA, 20, new byte[10]), equalTo(PutResult.STORED));
		assertThat(store.holding(first).isPresent(), is(false));
		assertThat(store.put(group(), FORMAT, SLOTS, Tier.EXTRA, 20, new byte[11]), equalTo(PutResult.QUOTA));
	}

	@SneakyThrows
	@Example
	public void tombstonesBlockStoresUntilTheyExpire() {
		final TestClock clock = new TestClock();
		final FragmentStore store = store(root(), clock, 100, 50);
		final GroupId id = group();

		store.put(id, FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[4]);
		store.delete(id, Duration.ofDays(1));

		assertThat(store.get(id).isPresent(), is(false));
		assertThat(store.put(id, FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[4]), equalTo(PutResult.TOMBSTONE));

		clock.advance(Duration.ofDays(2));
		store.purgeTombstones();
		assertThat(store.put(id, FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[4]), equalTo(PutResult.STORED));
	}

	@SneakyThrows
	@Example
	public void survivesARestart() {
		final Path root = root();
		final TestClock clock = new TestClock();
		final FragmentStore before = store(root, clock, 100, 100);
		final GroupId oldestExtra = group();
		final GroupId deleted = group();

		before.put(oldestExtra, FORMAT, SLOTS, Tier.EXTRA, 40, new byte[40]);
		before.put(group(), FORMAT, SLOTS, Tier.EXTRA, 41, new byte[40]);
		before.put(deleted, FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[1]);
		before.delete(deleted, Duration.ofDays(1));

		final FragmentStore after = store(root, clock, 100, 100);

		assertThat(after.usedBytes(), equalTo(80L));
		assertThat(after.isTombstoned(deleted), is(true));
		assertThat(after.put(group(), FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[30]), equalTo(PutResult.STORED));
		assertThat(after.holding(oldestExtra).isPresent(), is(false));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void neverExceedsItsLimits(@ForAll @IntRange(min = 10, max = 400) int maxBytes,
		@ForAll @IntRange(min = 0, max = 90) int pool,
		@ForAll @Size(max = 60) List<@IntRange(min = 1, max = 60) Integer> sizes, @ForAll long seed) {

		final FragmentStore store = store(root(), new TestClock(), maxBytes, pool);
		final Random choice = new Random(seed);

		for (int size : sizes) {
			store.put(group(), FORMAT, SLOTS, choice.nextBoolean() ? Tier.EXTRA : Tier.GUARANTEED, 0, new byte[size]);
			assertThat(store.usedBytes(), lessThanOrEqualTo((long) maxBytes));
			assertThat(store.extraBytes(), lessThanOrEqualTo((long) maxBytes * pool / 100));
		}
	}
}
