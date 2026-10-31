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

package org.unigrid.hedgehog.service.storage;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.ChunkGroups;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.GroupDescriptor;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.LayoutParameters;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.StorageStatus;
import org.unigrid.hedgehog.model.storage.TestClock;
import org.unigrid.hedgehog.model.storage.Fingerprint;
import org.unigrid.hedgehog.model.storage.GroupKey;
import org.unigrid.hedgehog.model.storage.placement.Placement;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;

public class GroupRepairerTest {
	private enum Forgery { MISSING, FOREIGN_KEY, FOREIGN_GROUP, BROKEN_SIGNATURE, VALID }

	@Provide
	Arbitrary<StorageSpork.SporkData> parameters() {
		return StorageArbitraries.parameters();
	}

	@Provide
	Arbitrary<StorageSpork.SporkData> parametersWithoutExtras() {
		return StorageArbitraries.parameters().map(parameters -> {
			parameters.setMaxParityPercent(parameters.getInnerParityPercent());
			return parameters;
		});
	}

	private static byte[] fileOf(StorageFleet fleet, Random random) {
		final byte[] file = new byte[random.nextInt(2 * fleet.getParameters().layout().payloadSize()) + 1];

		random.nextBytes(file);
		return file;
	}

	@SneakyThrows
	private static Fingerprint storeSomething(StorageFleet fleet, Random random) {
		return fleet.service(SeededSecureRandom.from(random)).store(new ByteArrayInputStream(fileOf(fleet, random)));
	}

	private static byte[] chunkOf(StorageFleet fleet, Random random) {
		final byte[] chunk = new byte[fleet.getParameters().getChunkSize()];

		random.nextBytes(chunk);
		return chunk;
	}

	private static GroupKey placeGroup(StorageFleet fleet, GroupKey key, Random random) {
		final StorageSpork.SporkData parameters = fleet.getParameters();

		new GroupDistributor(fleet.getTransport(), random, Duration.ZERO).place(ChunkGroups.seal(chunkOf(fleet,
			random), key, StorageFormat.current(), parameters.layout()),
			Placement.window(key.groupId(), fleet.getGridnodes(), parameters.window()));
		return key;
	}

	private static GroupKey placeGroup(StorageFleet fleet, Random random) {
		return placeGroup(fleet, StorageTestData.key(random), random);
	}

	private static List<Gridnode> holdersOf(StorageFleet fleet, GroupId groupId) {
		return fleet.online().stream().filter(g -> fleet.getStores().get(g.getId()).holding(groupId).isPresent())
			.collect(Collectors.toList());
	}

	private static int triggerOf(StorageSpork.SporkData parameters) {
		return Math.max(1, LayoutParameters.percentOf(parameters.layout().parityFragments(),
			parameters.getRepairThresholdPercent()));
	}

	private static int fragmentsIn(StorageFleet fleet) {
		return fleet.getStores().values().stream().mapToInt(store -> store.groups().size()).sum();
	}

	private static void runAlone(StorageFleet fleet, GroupRepairer repairer, int epochs) {
		for (int epoch = 0; epoch < epochs; epoch++) {
			fleet.getClock().advance(Duration.ofMinutes(fleet.getParameters().getRepairIntervalMinutes()));
			repairer.runEpoch();
		}
	}

	/* A liar answers every census as if it held the given index */
	private static void lieAbout(StorageFleet fleet, String liar, int index) {
		fleet.getTransport().lie(liar, entries -> entries.stream()
			.map(entry -> new FragmentStatus.Entry(entry.getGroupId(), FragmentStatus.State.HELD, index))
			.collect(Collectors.toList()));
	}

	private static void keep(StorageFleet fleet, Gridnode gridnode, Fragment fragment) {
		assertThat(fleet.getTransport().store(gridnode, fragment.encode()).join().getStatus(),
			equalTo(StorageStatus.OK));
	}

	private static StorageStatus deleteFrom(FragmentKeeper keeper, GroupKey key) {
		return keeper.delete(key.groupId(), key.publicKey(), 0, key.signDelete(0));
	}

	private static DeleteProof forge(Forgery forgery, GroupKey key, GroupKey other, long timestamp) {
		final byte[] broken = key.signDelete(timestamp);

		broken[0] ^= 1;

		return switch (forgery) {
			case MISSING -> null;
			case FOREIGN_KEY -> new DeleteProof(other.publicKey(), timestamp,
				other.sign(GroupKey.deleteMessage(key.groupId(), timestamp)));
			case FOREIGN_GROUP -> new DeleteProof(other.publicKey(), timestamp, other.signDelete(timestamp));
			case BROKEN_SIGNATURE -> new DeleteProof(key.publicKey(), timestamp, broken);
			case VALID -> new DeleteProof(key.publicKey(), timestamp, key.signDelete(timestamp));
		};
	}

	@Property(tries = 200)
	public void dutyVisitsEveryRankOncePerCycle(@ForAll @Size(32) byte[] group, @ForAll long start,
		@ForAll @IntRange(min = 1, max = 300) int width) {

		final GroupId groupId = GroupId.of(group);
		final long firstEpoch = Math.floorMod(start, Long.MAX_VALUE / 2);

		for (int rank = 0; rank < width; rank++) {
			final int candidate = rank;
			final long duties = LongStream.range(firstEpoch, firstEpoch + width)
				.filter(epoch -> GroupRepairer.isDuty(groupId, candidate, epoch, width)).count();

			assertThat(duties, equalTo(1L));
		}
	}

	@Property(tries = 200)
	public void spotChecksEveryHolderOnceInEveryFewDutyVisits(@ForAll @Size(32) byte[] group, @ForAll long start,
		@ForAll @IntRange(min = 1, max = 300) int width) {

		final GroupId groupId = GroupId.of(group);
		final long firstEpoch = Math.floorMod(start, Long.MAX_VALUE / 2);
		final int cycle = GroupRepairer.SPOT_CHECK_EVERY * width;

		for (int rank = 0; rank < width; rank++) {
			final int candidate = rank;
			final long checks = LongStream.range(firstEpoch, firstEpoch + cycle)
				.filter(epoch -> GroupRepairer.isDuty(groupId, candidate, epoch, width)
					&& GroupRepairer.isSpotCheckDue(groupId, epoch, width)).count();

			assertThat(checks, equalTo(1L));
		}
	}

	@Property(tries = 300)
	public void repairsExactlyFromTheThreshold(@ForAll @IntRange(min = 1, max = 64) int dataFragments,
		@ForAll @IntRange(min = 1, max = 64) int parityFragments,
		@ForAll @IntRange(min = 1, max = 100) int threshold, @ForAll @IntRange(min = 0, max = 64) int lost) {

		final GroupDescriptor descriptor = GroupDescriptor.builder().format(StorageFormat.current())
			.dataFragments(dataFragments).parityFragments(parityFragments)
			.maxFragments(dataFragments + parityFragments).fragmentSize(1).build();
		final int present = Math.max(0, dataFragments + parityFragments - lost);
		final int trigger = Math.max(1, (parityFragments * threshold + 99) / 100);

		assertThat(GroupRepairer.needsRepair(descriptor, present, threshold),
			equalTo(dataFragments + parityFragments - present >= trigger));
	}

	@Property(tries = 40)
	public void restoresGroupsOnlyOnceTheThresholdIsReached(
		@ForAll("parametersWithoutExtras") StorageSpork.SporkData parameters, @ForAll long seed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window() + 4);
		final int guaranteed = parameters.layout().guaranteedFragments();

		storeSomething(fleet, random);

		final GroupId groupId = new ArrayList<>(fleet.groups()).get(random.nextInt(fleet.groups().size()));
		final List<Gridnode> holders = new ArrayList<>(holdersOf(fleet, groupId));
		final int wiped = random.nextInt(parameters.layout().parityFragments() + 1);

		Collections.shuffle(holders, random);
		holders.subList(0, wiped).forEach(g -> fleet.wipe(g.getId()));
		fleet.runRepairEpochs(2 * parameters.window());

		assertThat(fleet.holdersOf(groupId),
			equalTo((long) (wiped >= triggerOf(parameters) ? guaranteed : guaranteed - wiped)));
	}

	@Property(tries = 90)
	public void repairsDespiteGridnodesClaimingFragmentsTheyLack(
		@ForAll("parametersWithoutExtras") StorageSpork.SporkData parameters, @ForAll long seed,
		@ForAll @IntRange(min = 1, max = 3) int liars) {

		final LayoutParameters layout = parameters.layout();
		final int lost = triggerOf(parameters) + liars - 1;

		Assume.that(lost <= layout.parityFragments() && liars <= parameters.getPlacementSlack());

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window() + 4);
		final GroupId groupId = placeGroup(fleet, random).groupId();
		final List<Gridnode> holders = new ArrayList<>(holdersOf(fleet, groupId));

		Collections.shuffle(holders, random);

		for (Gridnode liar : holders.subList(0, liars)) {
			lieAbout(fleet, liar.getId(), fleet.getStores().get(liar.getId()).holding(groupId).get().getIndex());
		}

		holders.subList(0, lost).forEach(g -> fleet.wipe(g.getId()));

		for (int epoch = 0; epoch < 80 * parameters.window()
			&& fleet.distinctIndicesOf(groupId, parameters.window()) < layout.guaranteedFragments(); epoch++) {

			fleet.runRepairEpochs(1);
		}

		assertThat(fleet.distinctIndicesOf(groupId, parameters.window()),
			equalTo((long) layout.guaranteedFragments()));
	}

	@Property(tries = 50)
	public void ignoresClaimsOutsideTheGroup(@ForAll("parametersWithoutExtras") StorageSpork.SporkData parameters,
		@ForAll long seed) {

		/* A liar never counts as a free target, so the rebuilt fragments need room elsewhere in the window */
		Assume.that(triggerOf(parameters) <= parameters.getPlacementSlack());

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window() + 4);
		final LayoutParameters layout = parameters.layout();
		final GroupId groupId = placeGroup(fleet, random).groupId();
		final List<Gridnode> holders = new ArrayList<>(holdersOf(fleet, groupId));

		Collections.shuffle(holders, random);

		for (Gridnode liar : holders.subList(0, triggerOf(parameters))) {
			lieAbout(fleet, liar.getId(), layout.maxFragments() + random.nextInt(256 - layout.maxFragments()));
			fleet.wipe(liar.getId());
		}

		fleet.runRepairEpochs(2 * parameters.window());

		assertThat(fleet.distinctIndicesOf(groupId, parameters.window()),
			equalTo((long) layout.guaranteedFragments()));
	}

	@Property(tries = 40)
	public void neverRebuildsAGroupBeyondTheQuota(
		@ForAll("parametersWithoutExtras") StorageSpork.SporkData parameters, @ForAll long seed,
		@ForAll boolean shrinkLayout) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window() + 4);
		final LayoutParameters layout = parameters.layout();
		final GroupId groupId = placeGroup(fleet, random).groupId();
		final List<Gridnode> holders = new ArrayList<>(holdersOf(fleet, groupId));
		final int fetches = fleet.getTransport().getFetches().get();

		Collections.shuffle(holders, random);
		holders.subList(0, layout.parityFragments()).forEach(g -> fleet.wipe(g.getId()));

		if (shrinkLayout) {
			parameters.setFragmentSize(1);
			parameters.setChunkSize(layout.dataFragments());
		} else {
			parameters.setMaxBytesPerNode((long) layout.maxFragments() * layout.getFragmentSize() - 1
				- random.nextInt(layout.getFragmentSize()));
		}

		fleet.runRepairEpochs(2 * parameters.window());

		assertThat(fleet.getTransport().getFetches().get(), equalTo(fetches));
		assertThat(fleet.holdersOf(groupId), equalTo((long) layout.dataFragments()));
	}

	@Property(tries = 30)
	@SneakyThrows
	public void keepsRepairingPastAGroupItCannotRebuild(
		@ForAll("parametersWithoutExtras") StorageSpork.SporkData parameters, @ForAll long seed,
		@ForAll boolean brokenFirst) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window() + 4);
		final GroupKey first = StorageTestData.key(random);
		final GroupKey second = StorageTestData.key(random);
		final boolean firstSortsLower = first.groupId().toHex().compareTo(second.groupId().toHex()) < 0;
		final GroupKey mixed = placeGroup(fleet, firstSortsLower == brokenFirst ? first : second, random);
		final GroupId healthy = placeGroup(fleet, firstSortsLower == brokenFirst ? second : first, random).groupId();
		final List<Gridnode> holders = new ArrayList<>(holdersOf(fleet, healthy));

		fleet.replace(mixed.groupId(), ChunkGroups.seal(chunkOf(fleet, random), mixed, StorageFormat.current(),
			parameters.layout()), gridnode -> random.nextBoolean());

		final List<Gridnode> mixedHolders = holdersOf(fleet, mixed.groupId());

		for (Gridnode gridnode : mixedHolders.subList(0, parameters.layout().parityFragments())) {
			fleet.getStores().get(gridnode.getId()).remove(mixed.groupId());
		}

		Collections.shuffle(holders, random);
		holders.subList(0, parameters.layout().parityFragments()).forEach(g -> fleet.wipe(g.getId()));
		fleet.runRepairEpochs(2 * parameters.window());

		assertThat(fleet.holdersOf(healthy), equalTo((long) parameters.layout().guaranteedFragments()));
	}

	@Property(tries = 40)
	public void deletesOnlyOnTombstonesSignedByTheGroupKey(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed, @ForAll Forgery forgery) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window() + 4);
		final GroupKey key = placeGroup(fleet, random);
		final DeleteProof proof = forge(forgery, key, StorageTestData.key(random), random.nextLong());
		final List<Gridnode> window = Placement.window(key.groupId(), fleet.getGridnodes(), parameters.window());
		final String liar = window.get(random.nextInt(window.size())).getId();
		final List<Gridnode> honest = holdersOf(fleet, key.groupId()).stream()
			.filter(gridnode -> !gridnode.getId().equals(liar)).collect(Collectors.toList());

		fleet.getTransport().lie(liar, entries -> entries.stream()
			.map(entry -> new FragmentStatus.Entry(entry.getGroupId(), FragmentStatus.State.TOMBSTONE, 0, proof))
			.collect(Collectors.toList()));
		fleet.runRepairEpochs(2 * parameters.window());

		for (Gridnode gridnode : honest) {
			final FragmentStore store = fleet.getStores().get(gridnode.getId());

			assertThat(store.isTombstoned(key.groupId()), is(forgery == Forgery.VALID));
			assertThat(store.holding(key.groupId()).isPresent(), is(forgery != Forgery.VALID));
		}
	}

	@Property(tries = 40)
	public void staleHoldersDropDeletedGroups(@ForAll("parameters") StorageSpork.SporkData parameters,
		@ForAll long seed) throws Exception {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window() + 4);
		final Fingerprint fingerprint = storeSomething(fleet, random);
		final List<Gridnode> absent = new ArrayList<>(fleet.getGridnodes());

		Collections.shuffle(absent, random);
		absent.subList(0, random.nextInt(parameters.layout().parityFragments()) + 1)
			.forEach(g -> fleet.getTransport().offline(g.getId()));
		fleet.service(SeededSecureRandom.from(random)).delete(fingerprint);
		absent.forEach(g -> fleet.getTransport().online(g.getId()));
		fleet.runRepairEpochs(2 * parameters.window());

		assertThat(fleet.groups().isEmpty(), is(true));
	}

	@Property(tries = 30)
	public void touchesNothingUntilItSeesItselfActive(@ForAll("parameters") StorageSpork.SporkData parameters,
		@ForAll long seed, @ForAll @IntRange(min = 1, max = 3) int joins) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());

		storeSomething(fleet, random);
		IntStream.range(0, joins).forEach(joined -> fleet.join());

		final List<Gridnode> holders = fleet.getGridnodes().stream()
			.filter(g -> !fleet.getStores().get(g.getId()).groups().isEmpty()).collect(Collectors.toList());
		final String self = holders.get(random.nextInt(holders.size())).getId();
		final FragmentStore store = fleet.getStores().get(self);
		final Set<GroupId> held = store.groups();
		final List<Gridnode> others = fleet.getGridnodes().stream().filter(g -> !g.getId().equals(self))
			.collect(Collectors.toList());
		final int fragments = fragmentsIn(fleet);

		runAlone(fleet, new GroupRepairer(store, new TopologyGridnodeDirectory(() -> others, () -> self),
			fleet.getTransport(), fleet::spork, fleet.getClock()), 2 * parameters.window());

		assertThat(store.groups(), equalTo(held));
		assertThat(fragmentsIn(fleet), equalTo(fragments));

		runAlone(fleet, fleet.repairer(self), 2 * parameters.window());

		assertThat(store.groups(), equalTo(held.stream().filter(groupId -> Placement.rankOf(groupId,
			fleet.getGridnodes(), self).getAsInt() < parameters.window()).collect(Collectors.toSet())));
	}

	@Property(tries = 10)
	@SneakyThrows
	public void freesExpiredTombstonesOnANodeThatIsNoGridnode(@ForAll long seed) {
		final Random random = new Random(seed);
		final StorageSpork.SporkData parameters = StorageTestData.parameters();
		final TestClock clock = new TestClock();
		final FragmentStore store = new FragmentStore(Jimfs.newFileSystem(Configuration.unix()).getPath("/fragments"),
			clock);
		final FragmentKeeper keeper = new FragmentKeeper(store, () -> Optional.of(parameters), Optional::empty);
		final GroupKey expiring = StorageTestData.key(random);
		final GroupKey later = StorageTestData.key(random);
		final long oneTombstoneQuota = 100 / FragmentStore.TOMBSTONE_BUDGET_PERCENT * FragmentStore.MIN_ENTRY_COST;

		parameters.setMaxBytesPerNode(oneTombstoneQuota);

		assertThat(deleteFrom(keeper, expiring), equalTo(StorageStatus.OK));
		assertThat(deleteFrom(keeper, later), equalTo(StorageStatus.QUOTA));

		clock.advance(Duration.ofDays(parameters.getTombstoneDays()).plusMinutes(1));
		new GroupRepairer(store, new TopologyGridnodeDirectory(List::of, () -> ""), new InMemoryTransport(),
			() -> Optional.of(parameters), clock).runEpoch();

		assertThat(deleteFrom(keeper, later), equalTo(StorageStatus.OK));
	}

	@Property(tries = 30)
	public void keepsFragmentsNoTargetReallyStored(@ForAll("parameters") StorageSpork.SporkData parameters,
		@ForAll long seed, @ForAll @IntRange(min = 1, max = 3) int joins) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());

		storeSomething(fleet, random);
		IntStream.range(0, joins).forEach(joined -> fleet.join());

		final Map<String, Set<GroupId>> held = fleet.getStores().entrySet().stream()
			.collect(Collectors.toMap(Map.Entry::getKey, store -> store.getValue().groups()));

		fleet.getGridnodes().forEach(gridnode -> fleet.getTransport().discard(gridnode.getId()));
		fleet.runRepairEpochs(2 * parameters.window());

		held.forEach((id, groups) -> assertThat(fleet.getStores().get(id).groups(), equalTo(groups)));
	}

	@Property(tries = 30)
	public void relocatesFragmentsIntoTheWindowAfterGridnodesJoin(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed,
		@ForAll @IntRange(min = 1, max = 3) int waves) throws Exception {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final StorageService service = fleet.service(SeededSecureRandom.from(random));
		final byte[] file = fileOf(fleet, random);
		final Fingerprint fingerprint = service.store(new ByteArrayInputStream(file));
		final ByteArrayOutputStream output = new ByteArrayOutputStream();

		for (int wave = 0; wave < waves; wave++) {
			for (int joined = 0; joined < parameters.getPlacementSlack(); joined++) {
				fleet.join();
			}

			fleet.runRepairEpochs(2 * parameters.window());
		}

		for (Gridnode gridnode : fleet.getGridnodes()) {
			final Set<GroupId> held = fleet.getStores().get(gridnode.getId()).groups();

			held.forEach(groupId -> assertThat(Placement.rankOf(groupId, fleet.getGridnodes(), gridnode.getId())
				.getAsInt(), lessThan(parameters.window())));
		}

		fleet.groups().forEach(groupId -> assertThat(fleet.distinctIndicesOf(groupId, parameters.window()),
			greaterThanOrEqualTo((long) parameters.layout().guaranteedFragments())));
		service.retrieve(fingerprint, output);
		assertThat(output.toByteArray(), equalTo(file));
	}

	/* A repair counts only the window, so it may rebuild the index of a holder that a join pushed out of the
	   window and leave no free member for that holder to hand its copy over to */
	@Property(tries = 30)
	public void dropsCopiesOutsideTheWindowThatTheWindowAlreadyHolds(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed) {

		final Random random = new Random(seed);
		final Scene scene = Scene.of(parameters, random, 1);

		scene.fill(scene.window(), scene.sealed());
		scene.keep(scene.outsider(), random.nextInt(scene.sealed().size()));
		scene.fleet().runRepairEpochs(2 * parameters.window());

		assertThat(scene.holds(scene.outsider()), is(false));
		assertThat(scene.distinctIndices(), equalTo((long) scene.sealed().size()));
	}

	/* The same group key can seal a group again, so a fragment at the same index from another seal is no copy of
	   the one held outside the window */
	@Property(tries = 30)
	public void keepsCopiesOutsideTheWindowThatOnlyAnotherSealHolds(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed) {

		final Random random = new Random(seed);
		final Scene scene = Scene.of(parameters, random, 1);

		scene.fill(scene.window(), scene.resealed());
		scene.keep(scene.outsider(), random.nextInt(scene.sealed().size()));
		scene.fleet().runRepairEpochs(2 * parameters.window());

		assertThat(scene.holds(scene.outsider()), is(true));
	}

	/* A member that holds nothing still counts as taken while it claims to hold the index */
	@Property(tries = 30)
	public void keepsCopiesOutsideTheWindowThatClaimantsCannotServe(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed) {

		final Random random = new Random(seed);
		final Scene scene = Scene.of(parameters, random, 1);
		final int index = random.nextInt(scene.sealed().size());
		final int liars = random.nextInt(scene.window().size()) + 1;

		scene.fill(scene.window().subList(liars, scene.window().size()), scene.sealedWithout(index));
		scene.window().subList(0, liars).forEach(liar -> lieAbout(scene.fleet(), liar.getId(), index));
		scene.keep(scene.outsider(), index);
		scene.fleet().runRepairEpochs(2 * parameters.window());

		assertThat(scene.holds(scene.outsider()), is(true));
	}

	@Property(tries = 30)
	public void keepsCopiesOutsideTheWindowThatClaimantsServeAtAnotherIndex(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed) {

		final Random random = new Random(seed);
		final Scene scene = Scene.of(parameters, random, 1);
		final int index = random.nextInt(scene.sealed().size());
		final int liars = random.nextInt(scene.window().size()) + 1;

		scene.fill(scene.window(), scene.sealedWithout(index));
		scene.window().subList(0, liars).forEach(liar -> lieAbout(scene.fleet(), liar.getId(), index));
		scene.keep(scene.outsider(), index);
		scene.fleet().runRepairEpochs(2 * parameters.window());

		assertThat(scene.holds(scene.outsider()), is(true));
	}

	@Property(tries = 30)
	public void keepsCopiesOutsideTheWindowThatClaimantsServeTampered(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed) {

		final Random random = new Random(seed);
		final Scene scene = Scene.of(parameters, random, 1);
		final int index = random.nextInt(scene.sealed().size());
		final Gridnode claimant = scene.window().get(random.nextInt(scene.window().size()));

		scene.fill(scene.window().stream().filter(member -> !member.equals(claimant)).collect(Collectors.toList()),
			scene.sealedWithout(index));
		scene.keep(claimant, index);
		scene.fleet().getTransport().tamper(claimant.getId(), bytes -> {
			bytes[bytes.length - 1] ^= 1;
			return bytes;
		});
		scene.keep(scene.outsider(), index);
		scene.fleet().runRepairEpochs(2 * parameters.window());

		assertThat(scene.holds(scene.outsider()), is(true));
	}

	/* A copy the window already serves only goes, rather than taking up a free member as a duplicate */
	@Property(tries = 30)
	public void dropsCopiesOutsideTheWindowWithoutFillingTheSlack(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed) {

		Assume.that(parameters.getPlacementSlack() > 0);

		final Random random = new Random(seed);
		final Scene scene = Scene.of(parameters, random, 1);

		scene.fill(scene.window().subList(0, scene.sealed().size()), scene.sealed());
		scene.keep(scene.outsider(), random.nextInt(scene.sealed().size()));
		scene.fleet().runRepairEpochs(2 * parameters.window());

		assertThat(scene.holds(scene.outsider()), is(false));
		assertThat(scene.fleet().holdersOf(scene.key().groupId()), equalTo((long) scene.sealed().size()));
	}

	@Property(tries = 30)
	public void dropsEveryCopyOutsideTheWindowOfAnIndexTheWindowHolds(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed) {

		final Random random = new Random(seed);
		final Scene scene = Scene.of(parameters, random, 2);
		final int index = random.nextInt(scene.sealed().size());

		scene.fill(scene.window(), scene.sealed());
		scene.outsiders().forEach(outsider -> scene.keep(outsider, index));
		scene.fleet().runRepairEpochs(2 * parameters.window());

		scene.outsiders().forEach(outsider -> assertThat(scene.holds(outsider), is(false)));
		assertThat(scene.distinctIndices(), equalTo((long) scene.sealed().size()));
	}

	/* A target that stores the copy but serves another seal at its index has not taken it over */
	@Property(tries = 30)
	public void keepsCopiesOutsideTheWindowThatTargetsServeOfAnotherSeal(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed) {

		Assume.that(parameters.getPlacementSlack() > 0);

		final Random random = new Random(seed);
		final Scene scene = Scene.of(parameters, random, 1);
		final int index = random.nextInt(scene.sealed().size());
		final int taken = scene.sealed().size();
		final byte[] other = scene.resealed().get(index).encode();

		scene.fill(scene.window().subList(0, taken - 1), scene.sealedWithout(index));
		scene.fill(scene.window().subList(taken - 1, taken), List.of(scene.resealed().get(index)));
		scene.window().subList(taken, scene.window().size())
			.forEach(target -> scene.fleet().getTransport().tamper(target.getId(), bytes -> other.clone()));
		scene.keep(scene.outsider(), index);
		scene.fleet().runRepairEpochs(2 * parameters.window());

		assertThat(scene.holds(scene.outsider()), is(true));
	}

	/* A group sealed twice under one key and a fleet with the given number of gridnodes beyond its window, where
	   nothing is stored until a test keeps it */
	private record Scene(StorageSpork.SporkData parameters, StorageFleet fleet, GroupKey key, List<Fragment> sealed,
		List<Fragment> resealed, List<Gridnode> window, List<Gridnode> outsiders) {

		static Scene of(StorageSpork.SporkData parameters, Random random, int outsiders) {
			final StorageFleet fleet = new StorageFleet(parameters, parameters.window() + outsiders);
			final GroupKey key = StorageTestData.key(random);
			final List<Fragment> sealed = ChunkGroups.seal(chunkOf(fleet, random), key, StorageFormat.current(),
				parameters.layout());
			final List<Fragment> resealed = ChunkGroups.seal(chunkOf(fleet, random), key, StorageFormat.current(),
				parameters.layout());
			final List<Gridnode> window = Placement.window(key.groupId(), fleet.getGridnodes(), parameters.window());

			return new Scene(parameters, fleet, key, sealed, resealed, window, fleet.getGridnodes().stream()
				.filter(gridnode -> !window.contains(gridnode)).collect(Collectors.toList()));
		}

		Gridnode outsider() {
			return outsiders.get(0);
		}

		List<Fragment> sealedWithout(int index) {
			return sealed.stream().filter(fragment -> fragment.getIndex() != index).collect(Collectors.toList());
		}

		/* Hands the members the fragments in turn, starting over once every fragment is out */
		void fill(List<Gridnode> members, List<Fragment> fragments) {
			IntStream.range(0, members.size()).forEach(i -> GroupRepairerTest.keep(fleet, members.get(i),
				fragments.get(i % fragments.size())));
		}

		void keep(Gridnode gridnode, int index) {
			GroupRepairerTest.keep(fleet, gridnode, sealed.get(index));
		}

		boolean holds(Gridnode gridnode) {
			return fleet.getStores().get(gridnode.getId()).holding(key.groupId()).isPresent();
		}

		long distinctIndices() {
			return fleet.distinctIndicesOf(key.groupId(), parameters.window());
		}
	}
}
