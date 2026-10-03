/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation, UGD Software AB

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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import lombok.SneakyThrows;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.GroupId;
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
	public void rejectsAnySingleBitFlip(@ForAll long seed, @ForAll @IntRange(min = 0, max = 15) int index,
		@ForAll @IntRange(min = 0, max = 100_000) int position, @ForAll @IntRange(min = 0, max = 7) int bit) {

		final Random random = new Random(seed);
		final byte[] tampered = StorageTestData.group(StorageTestData.key(random), random).get(index).encode();
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
		assertThat(keeper.delete(key.groupId(), key.publicKey(), 1, key.signDelete(1)), equalTo(StorageStatus.DISABLED));
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
	public void rejectsTruncatedOrExtendedFragments(@ForAll long seed, @ForAll @IntRange(min = 0, max = 15) int index,
		@ForAll @IntRange(min = -64, max = 64) int change) {

		Assume.that(change != 0);

		final Random random = new Random(seed);
		final byte[] encoded = StorageTestData.group(StorageTestData.key(random), random).get(index).encode();
		final byte[] resized = Arrays.copyOf(encoded, Math.max(0, encoded.length + change));

		assertThat(keeper(store()).store(resized), equalTo(StorageStatus.INVALID));
	}

	@Property(tries = 30)
	public void answersQuotaWhenAFragmentDoesNotFit(@ForAll long seed, @ForAll @IntRange(min = 0, max = 15) int index,
		@ForAll @IntRange(min = 0, max = 100_000) int room) {

		final Random random = new Random(seed);
		final GroupKey key = StorageTestData.key(random);
		final byte[] encoded = StorageTestData.group(key, random).get(index).encode();
		final StorageSpork.SporkData parameters = StorageTestData.parameters();
		parameters.setMaxBytesPerNode(room % encoded.length);

		final FragmentKeeper keeper = new FragmentKeeper(store(), () -> Optional.of(parameters));

		assertThat(keeper.store(encoded), equalTo(StorageStatus.QUOTA));
		assertThat(keeper.census(List.of(key.groupId())).get(0).getState(), equalTo(FragmentStatus.State.NONE));
	}

	@Property(tries = 30)
	public void knowsNothingOfGroupsItNeverSaw(@ForAll long seed) {
		final GroupKey key = StorageTestData.key(new Random(seed));
		final FragmentKeeper keeper = keeper(store());

		assertThat(keeper.fetch(key.groupId()).isPresent(), is(false));
		assertThat(keeper.census(List.of(key.groupId())).get(0),
			equalTo(new FragmentStatus.Entry(key.groupId(), FragmentStatus.State.NONE, 0)));
	}

	@Property(tries = 100)
	public void refusesForgedDeletes(@ForAll long seed, @ForAll long timestamp, @ForAll Forgery forgery,
		@ForAll boolean held) {

		final Random random = new Random(seed);
		final GroupKey key = StorageTestData.key(random);
		final GroupKey stranger = StorageTestData.key(random);
		final FragmentKeeper keeper = keeper(store());
		final Forged forged = forgery.forge(key, stranger, timestamp);

		if (held) {
			keeper.store(StorageTestData.group(key, random).get(0).encode());
		}

		assertThat(keeper.delete(forged.target(), forged.publicKey(), timestamp, forged.signature()),
			equalTo(StorageStatus.INVALID));
		assertThat(keeper.census(List.of(key.groupId(), forged.target())).stream().map(FragmentStatus.Entry::getState)
			.anyMatch(FragmentStatus.State.TOMBSTONE::equals), is(false));
		assertThat(keeper.fetch(key.groupId()).isPresent(), is(held));
	}

	@Property(tries = 50)
	public void tombstonesAnyGroupWithAValidProof(@ForAll long seed, @ForAll long timestamp,
		@ForAll @IntRange(min = 0, max = 15) int index, @ForAll boolean held) {

		final Random random = new Random(seed);
		final GroupKey key = StorageTestData.key(random);
		final Fragment fragment = StorageTestData.group(key, random).get(index);
		final FragmentStore store = store();
		final FragmentKeeper keeper = keeper(store);
		final byte[] signature = key.signDelete(timestamp);

		if (held) {
			keeper.store(fragment.encode());
		}

		assertThat(keeper.delete(key.groupId(), key.publicKey(), timestamp, signature), equalTo(StorageStatus.OK));
		assertThat(keeper.fetch(key.groupId()).isPresent(), is(false));
		assertThat(store.holding(key.groupId()).isPresent(), is(false));

		final FragmentStatus.Entry entry = keeper.census(List.of(key.groupId())).get(0);

		assertThat(entry.getState(), equalTo(FragmentStatus.State.TOMBSTONE));
		assertThat(entry.getProof(), equalTo(Optional.of(new DeleteProof(key.publicKey(), timestamp, signature))));
		assertThat(entry.getProof().get().verifies(key.groupId()), is(true));
		assertThat(keeper.store(fragment.encode()), equalTo(StorageStatus.TOMBSTONE));
	}

	@Property(tries = 30)
	public void acceptsAnyDeleteOfATombstonedGroup(@ForAll long seed, @ForAll long timestamp,
		@ForAll @Size(max = 128) byte[] publicKey, @ForAll long otherTimestamp, @ForAll @Size(max = 128) byte[] signature) {

		final Random random = new Random(seed);
		final GroupKey key = StorageTestData.key(random);
		final FragmentKeeper keeper = keeper(store());
		final DeleteProof proof = new DeleteProof(key.publicKey(), timestamp, key.signDelete(timestamp));

		keeper.delete(key.groupId(), proof.getPublicKey(), timestamp, proof.getSignature());

		assertThat(keeper.delete(key.groupId(), publicKey, otherTimestamp, signature), equalTo(StorageStatus.OK));
		assertThat(keeper.census(List.of(key.groupId())).get(0).getProof(), equalTo(Optional.of(proof)));
	}

	private record Forged(GroupId target, byte[] publicKey, byte[] signature) { }

	private enum Forgery {
		STRANGER_KEY, STRANGER_SIGNATURE, OTHER_TIMESTAMP, OTHER_GROUP, EMPTY_SIGNATURE, SHORT_SIGNATURE,
		LONG_SIGNATURE, SHORT_KEY;

		Forged forge(GroupKey key, GroupKey stranger, long timestamp) {
			final GroupId group = key.groupId();
			final byte[] signature = key.signDelete(timestamp);

			return switch (this) {
				case STRANGER_KEY -> new Forged(group, stranger.publicKey(),
					stranger.sign(GroupKey.deleteMessage(group, timestamp)));
				case STRANGER_SIGNATURE -> new Forged(group, key.publicKey(),
					stranger.sign(GroupKey.deleteMessage(group, timestamp)));
				case OTHER_TIMESTAMP -> new Forged(group, key.publicKey(), key.signDelete(timestamp + 1));
				case OTHER_GROUP -> new Forged(stranger.groupId(), key.publicKey(), signature);
				case EMPTY_SIGNATURE -> new Forged(group, key.publicKey(), new byte[0]);
				case SHORT_SIGNATURE -> new Forged(group, key.publicKey(), Arrays.copyOf(signature, signature.length - 1));
				case LONG_SIGNATURE -> new Forged(group, key.publicKey(), Arrays.copyOf(signature, signature.length + 1));
				case SHORT_KEY -> new Forged(group, Arrays.copyOf(key.publicKey(), GroupKey.PUBLIC_KEY_SIZE - 1), signature);
			};
		}
	}
}
