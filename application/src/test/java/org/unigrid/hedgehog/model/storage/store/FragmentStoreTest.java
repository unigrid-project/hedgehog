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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.TestClock;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.Holding;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.PutResult;
import org.unigrid.hedgehog.model.storage.store.FragmentStore.Tier;

public class FragmentStoreTest {
	private static final StorageFormat FORMAT = StorageFormat.current();
	private static final int SLOTS = 32;
	private static final long UNLIMITED = 1_000_000;
	private static final int HEADER_SIZE = 12;
	private static final int POOL_OF_IDS = 8;
	private static final int TOMBSTONE_SIZE = 2 * Long.BYTES + GroupKey.PUBLIC_KEY_SIZE + GroupKey.SIGNATURE_SIZE;
	private final Random random = new Random(1);

	private record Fragment(GroupId id, StorageFormat format, int slots, Tier tier, int index, byte[] payload) { }

	private enum Kind { PUT, REMOVE, DELETE, PURGE, ADVANCE, RESTART }

	private record Operation(Kind kind, int target, Tier tier, int size, int minutes) { }

	private static Path root() {
		return Jimfs.newFileSystem(Configuration.unix()).getPath("/data/fragments");
	}

	private GroupId group() {
		return GroupId.of(bytes(GroupId.SIZE));
	}

	private byte[] bytes(int size) {
		final byte[] bytes = new byte[size];
		random.nextBytes(bytes);
		return bytes;
	}

	/* The store keeps whatever well-formed proof it is given; checking it is the caller's job */
	private DeleteProof proof() {
		return new DeleteProof(bytes(GroupKey.PUBLIC_KEY_SIZE), random.nextLong(), bytes(GroupKey.SIGNATURE_SIZE));
	}

	@SneakyThrows
	private static FragmentStore store(Path root, TestClock clock, long maxBytes, int extraPoolPercent) {
		final FragmentStore store = new FragmentStore(root, clock);
		store.limits(maxBytes, extraPoolPercent);
		return store;
	}

	@SneakyThrows
	private static void putAll(FragmentStore store, List<Fragment> fragments) {
		for (Fragment f : fragments) {
			store.put(f.id(), f.format(), f.slots(), f.tier(), f.index(), f.payload());
		}
	}

	private static Map<GroupId, Holding> snapshot(FragmentStore store) {
		return store.groups().stream().collect(Collectors.toMap(Function.identity(), id -> store.holding(id).get()));
	}

	@SneakyThrows
	private static Path plant(Path file, byte[] content) {
		Files.createDirectories(file.getParent());
		return Files.write(file, content);
	}

	private static Path fragmentPath(Path root, GroupId id) {
		final String hex = id.toHex();
		return root.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex + ".frag");
	}

	@Provide
	public Arbitrary<List<Fragment>> fragments() {
		final Arbitrary<GroupId> ids = Arbitraries.bytes().array(byte[].class).ofSize(GroupId.SIZE).map(GroupId::of);
		final Arbitrary<Integer> unsignedBytes = Arbitraries.integers().between(0, 255);

		return Combinators.combine(ids, Arbitraries.of(StorageFormat.class), unsignedBytes, Arbitraries.of(Tier.class),
			unsignedBytes, Arbitraries.bytes().array(byte[].class).ofMinSize(1).ofMaxSize(60))
			.as(Fragment::new).list().ofMaxSize(40);
	}

	@Provide
	public Arbitrary<List<Operation>> operations() {
		final Arbitrary<Kind> kinds = Arbitraries.frequency(Tuple.of(8, Kind.PUT), Tuple.of(2, Kind.REMOVE),
			Tuple.of(2, Kind.DELETE), Tuple.of(1, Kind.PURGE), Tuple.of(2, Kind.ADVANCE), Tuple.of(1, Kind.RESTART));

		return Combinators.combine(kinds, Arbitraries.integers().between(0, POOL_OF_IDS - 1), Arbitraries.of(Tier.class),
			Arbitraries.integers().between(0, 60), Arbitraries.integers().between(1, 3000))
			.as(Operation::new).list().ofMaxSize(80);
	}

	@Provide
	public Arbitrary<Integer> outsideAByte() {
		return Arbitraries.oneOf(Arbitraries.integers().lessOrEqual(-1), Arbitraries.integers().greaterOrEqual(256));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void storesAndReturnsFragments(@ForAll @Size(max = 200) byte[] payload,
		@ForAll @IntRange(min = 0, max = 255) int index, @ForAll @IntRange(min = 0, max = 255) int slots,
		@ForAll Tier tier) {

		final FragmentStore store = store(root(), new TestClock(), 1000, 50);
		final GroupId id = group();

		assertThat(store.put(id, FORMAT, slots, tier, index, payload), equalTo(PutResult.STORED));
		assertThat(store.get(id).get(), equalTo(payload));
		assertThat(store.holding(id).get(), equalTo(Holding.builder().format(FORMAT).slots(slots).tier(tier)
			.index(index).size(payload.length).sequence(0).build()));
		assertThat(store.put(id, FORMAT, slots, tier, index, new byte[] { 4 }), equalTo(PutResult.DUPLICATE));
	}

	@Property(tries = 100)
	public void rejectsSlotsAndIndicesOutsideAByte(@ForAll("outsideAByte") int value) {
		final FragmentStore store = store(root(), new TestClock(), 1000, 50);

		assertThrows(IllegalArgumentException.class,
			() -> store.put(group(), FORMAT, value, Tier.GUARANTEED, 0, new byte[1]));
		assertThrows(IllegalArgumentException.class,
			() -> store.put(group(), FORMAT, SLOTS, Tier.GUARANTEED, value, new byte[1]));
		assertThat(store.groups().isEmpty(), is(true));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void restoresEveryHoldingAfterARestart(@ForAll("fragments") List<Fragment> fragments) {
		final Path root = root();
		final FragmentStore before = store(root, new TestClock(), UNLIMITED, 100);

		putAll(before, fragments);
		final FragmentStore after = store(root, new TestClock(), UNLIMITED, 100);

		assertThat(after.groups(), equalTo(before.groups()));
		assertThat(snapshot(after), equalTo(snapshot(before)));
		assertThat(after.usedBytes(), equalTo(before.usedBytes()));
		assertThat(after.extraBytes(), equalTo(before.extraBytes()));

		for (GroupId id : before.groups()) {
			assertThat(after.get(id).get(), equalTo(before.get(id).get()));
		}
	}

	@SneakyThrows
	@Property(tries = 50)
	public void evictsExtrasOldestFirstAfterARestart(@ForAll("fragments") List<Fragment> fragments) {
		final Path root = root();

		putAll(store(root, new TestClock(), UNLIMITED, 100), fragments);
		final FragmentStore after = store(root, new TestClock(), UNLIMITED, 100);
		final List<GroupId> extrasByAge = snapshot(after).entrySet().stream()
			.filter(entry -> entry.getValue().getTier() == Tier.EXTRA)
			.sorted(Comparator.comparingLong(entry -> entry.getValue().getSequence()))
			.map(Map.Entry::getKey).collect(Collectors.toList());

		for (int i = 0; i < extrasByAge.size(); i++) {
			after.limits(after.usedBytes(), 100);

			assertThat(after.put(group(), FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[1]), equalTo(PutResult.STORED));
			assertThat(after.holding(extrasByAge.get(i)).isPresent(), is(false));
			assertThat(after.groups().containsAll(extrasByAge.subList(i + 1, extrasByAge.size())), is(true));
		}

		assertThat(after.extraBytes(), equalTo(0L));
	}

	@SneakyThrows
	@Property(tries = 100)
	public void tombstonesBlockStoresUntilTheyExpire(@ForAll @IntRange(min = 1, max = 100_000) int keepMinutes,
		@ForAll @Size(max = 20) List<@IntRange(min = 0, max = 20_000) Integer> advances,
		@ForAll boolean purge, @ForAll boolean restart) {

		final Path root = root();
		final TestClock clock = new TestClock();
		final GroupId id = group();
		final DeleteProof proof = proof();
		FragmentStore store = store(root, clock, 1000, 50);
		long elapsed = 0;

		store.put(id, FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[4]);
		store.delete(id, Duration.ofMinutes(keepMinutes), proof);
		assertThat(store.get(id).isPresent(), is(false));
		assertThat(store.tombstone(id), equalTo(Optional.of(proof)));

		for (int minutes : advances) {
			clock.advance(Duration.ofMinutes(minutes));
			elapsed += minutes;

			if (purge) {
				store.purgeTombstones();
			}

			if (restart) {
				store = store(root, clock, 1000, 50);
			}

			assertThat(store.isTombstoned(id), is(elapsed < keepMinutes));
			assertThat(store.tombstone(id), equalTo(elapsed < keepMinutes ? Optional.of(proof) : Optional.empty()));
		}

		final PutResult expected = elapsed < keepMinutes ? PutResult.TOMBSTONE : PutResult.STORED;
		assertThat(store.put(id, FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[4]), equalTo(expected));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void discardsUnreadableFilesOnStartup(@ForAll("fragments") List<Fragment> fragments,
		@ForAll @Size(max = HEADER_SIZE - 1) byte[] truncated, @ForAll @Size(min = HEADER_SIZE, max = 40) byte[] garbage,
		@ForAll @IntRange(min = 2, max = 127) int badTier, @ForAll @Size(max = TOMBSTONE_SIZE - 1) byte[] shortTombstone,
		@ForAll @Size(min = TOMBSTONE_SIZE + 1, max = 2 * TOMBSTONE_SIZE) byte[] longTombstone) {

		final Path root = root();
		final FragmentStore before = store(root, new TestClock(), UNLIMITED, 100);
		final byte[] badFormat = garbage.clone();
		final byte[] unknownTier = garbage.clone();

		putAll(before, fragments);
		badFormat[0] = 0;
		unknownTier[0] = FORMAT.getId();
		unknownTier[2] = (byte) badTier;

		final List<Path> planted = List.of(plant(fragmentPath(root, group()), truncated),
			plant(fragmentPath(root, group()), badFormat), plant(fragmentPath(root, group()), unknownTier),
			plant(root.resolve("00").resolve("00").resolve("not-hex.frag"), new byte[HEADER_SIZE]),
			plant(root.resolve("tombstones").resolve(group().toHex()), shortTombstone),
			plant(root.resolve("tombstones").resolve(group().toHex()), longTombstone),
			plant(root.resolve("tombstones").resolve("not-hex"), new byte[TOMBSTONE_SIZE]));

		final FragmentStore after = store(root, new TestClock(), UNLIMITED, 100);

		for (Path file : planted) {
			assertThat(Files.exists(file), is(false));
		}

		assertThat(snapshot(after), equalTo(snapshot(before)));
		assertThat(after.usedBytes(), equalTo(before.usedBytes()));
	}

	@Provide
	public Arbitrary<DeleteProof> malformedProofs() {
		final Arbitrary<byte[]> anyBytes = Arbitraries.bytes().array(byte[].class).ofMaxSize(2 * GroupKey.SIGNATURE_SIZE);

		return Combinators.combine(anyBytes, Arbitraries.longs(), anyBytes).as(DeleteProof::new)
			.filter(proof -> !proof.isWellFormed());
	}

	@SneakyThrows
	@Property(tries = 50)
	public void refusesMalformedProofs(@ForAll("malformedProofs") DeleteProof malformed) {
		final Path root = root();
		final FragmentStore store = store(root, new TestClock(), 1000, 50);
		final GroupId id = group();

		store.put(id, FORMAT, SLOTS, Tier.GUARANTEED, 0, new byte[4]);
		assertThrows(IllegalArgumentException.class, () -> store.delete(id, Duration.ofMinutes(1), malformed));
		assertThat(store.isTombstoned(id), is(false));
		assertThat(store.holding(id).isPresent(), is(true));

		try (Stream<Path> tombstones = Files.list(root.resolve("tombstones"))) {
			assertThat(tombstones.count(), equalTo(0L));
		}
	}

	@Property(tries = 200)
	public void behavesLikeItsModel(@ForAll @IntRange(min = 10, max = 400) int maxBytes,
		@ForAll @IntRange(min = 0, max = 100) int extraPoolPercent, @ForAll("operations") List<Operation> operations) {

		final Harness harness = new Harness(maxBytes, extraPoolPercent);

		for (Operation operation : operations) {
			harness.apply(operation);
		}
	}

	private static final class Model {
		private final Map<GroupId, Holding> holdings = new HashMap<>();
		private final Map<GroupId, Instant> tombstones = new HashMap<>();
		private final Map<GroupId, DeleteProof> proofs = new HashMap<>();
		private final long maxBytes;
		private final long extraLimit;
		private long sequence;

		Model(long maxBytes, int extraPoolPercent) {
			this.maxBytes = maxBytes;
			this.extraLimit = maxBytes * extraPoolPercent / 100;
		}

		long bytes(Tier... tiers) {
			return holdings.values().stream().filter(holding -> Arrays.asList(tiers).contains(holding.getTier()))
				.mapToLong(Holding::getSize).sum();
		}

		boolean isTombstoned(GroupId id, Instant now) {
			final Instant expiry = tombstones.get(id);
			return expiry != null && now.isBefore(expiry);
		}

		PutResult put(GroupId id, Tier tier, int size, Instant now) {
			final long room = maxBytes - bytes(Tier.GUARANTEED);

			if (isTombstoned(id, now)) {
				return PutResult.TOMBSTONE;
			} else if (holdings.containsKey(id)) {
				return PutResult.DUPLICATE;
			} else if (size > (tier == Tier.GUARANTEED ? room : Math.min(room, extraLimit))) {
				return PutResult.QUOTA;
			}

			while (bytes(Tier.values()) + size > maxBytes || tier == Tier.EXTRA && bytes(Tier.EXTRA) + size > extraLimit) {
				holdings.remove(oldestExtra());
			}

			holdings.put(id, Holding.builder().format(FORMAT).slots(SLOTS).tier(tier).index(0).size(size)
				.sequence(sequence++).build());
			return PutResult.STORED;
		}

		/* A restarted store only needs its new sequences to be younger than the fragments it still holds. */
		void restart() {
			sequence = holdings.values().stream().mapToLong(Holding::getSequence).max().orElse(-1) + 1;
		}

		private GroupId oldestExtra() {
			return holdings.entrySet().stream().filter(entry -> entry.getValue().getTier() == Tier.EXTRA)
				.min(Comparator.comparingLong(entry -> entry.getValue().getSequence())).get().getKey();
		}
	}

	private final class Harness {
		private final Path root = root();
		private final TestClock clock = new TestClock();
		private final List<GroupId> ids = Stream.generate(FragmentStoreTest.this::group).limit(POOL_OF_IDS)
			.collect(Collectors.toList());
		private final long maxBytes;
		private final int extraPoolPercent;
		private final Model model;
		private FragmentStore store;

		Harness(long maxBytes, int extraPoolPercent) {
			this.maxBytes = maxBytes;
			this.extraPoolPercent = extraPoolPercent;
			this.model = new Model(maxBytes, extraPoolPercent);
			this.store = store(root, clock, maxBytes, extraPoolPercent);
		}

		@SneakyThrows
		void apply(Operation operation) {
			final GroupId id = ids.get(operation.target());

			switch (operation.kind()) {
				case PUT -> put(id, operation.tier(), operation.size());
				case REMOVE -> assertThat(store.remove(id), is(model.holdings.remove(id) != null));
				case DELETE -> delete(id, Duration.ofMinutes(operation.minutes()));
				case PURGE -> store.purgeTombstones();
				case ADVANCE -> clock.advance(Duration.ofMinutes(operation.minutes()));
				default -> restart();
			}

			verify();
		}

		@SneakyThrows
		private void put(GroupId id, Tier tier, int size) {
			final Map<GroupId, Holding> before = snapshot(store);
			final PutResult expected = model.put(id, tier, size, clock.instant());

			assertThat(store.put(id, FORMAT, SLOTS, tier, 0, new byte[size]), equalTo(expected));

			if (expected != PutResult.STORED) {
				assertThat(snapshot(store), equalTo(before));
			}
		}

		private void restart() {
			store = store(root, clock, maxBytes, extraPoolPercent);
			model.restart();
		}

		@SneakyThrows
		private void delete(GroupId id, Duration keep) {
			final DeleteProof proof = proof();

			model.tombstones.put(id, clock.instant().plus(keep));
			model.proofs.put(id, proof);
			model.holdings.remove(id);
			store.delete(id, keep, proof);
		}

		private void verify() {
			assertThat(snapshot(store), equalTo(model.holdings));
			assertThat(store.usedBytes(), equalTo(model.bytes(Tier.values())));
			assertThat(store.extraBytes(), equalTo(model.bytes(Tier.EXTRA)));
			assertThat(store.usedBytes(), lessThanOrEqualTo(maxBytes));
			assertThat(store.extraBytes(), lessThanOrEqualTo(model.extraLimit));

			for (GroupId id : ids) {
				final boolean tombstoned = model.isTombstoned(id, clock.instant());

				assertThat(store.isTombstoned(id), is(tombstoned));
				assertThat(store.tombstone(id), equalTo(tombstoned ? Optional.of(model.proofs.get(id)) : Optional.empty()));
			}
		}
	}
}
