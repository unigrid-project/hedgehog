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

import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.ChunkGroups;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.GroupDescriptor;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.LayoutParameters;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprint;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
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

	@SneakyThrows
	private static Fingerprint storeSomething(StorageFleet fleet, Random random) {
		final byte[] file = new byte[random.nextInt(2 * fleet.getParameters().layout().payloadSize()) + 1];

		random.nextBytes(file);
		return fleet.service(new SecureRandom()).store(new ByteArrayInputStream(file));
	}

	private static byte[] chunkOf(StorageFleet fleet, Random random) {
		final byte[] chunk = new byte[fleet.getParameters().getChunkSize()];

		random.nextBytes(chunk);
		return chunk;
	}

	private static GroupKey placeGroup(StorageFleet fleet, Random random) {
		final StorageSpork.SporkData parameters = fleet.getParameters();
		final GroupKey key = StorageTestData.key(random);

		new GroupDistributor(fleet.getTransport(), random, Duration.ZERO).place(ChunkGroups.seal(chunkOf(fleet, random),
			key, StorageFormat.current(), parameters.layout()),
			Placement.window(key.groupId(), fleet.getGridnodes(), parameters.window()));
		return key;
	}

	private static List<Gridnode> holdersOf(StorageFleet fleet, GroupId groupId) {
		return fleet.online().stream().filter(g -> fleet.getStores().get(g.getId()).holding(groupId).isPresent())
			.collect(Collectors.toList());
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

	@Property(tries = 300)
	public void repairsExactlyFromTheThreshold(@ForAll @IntRange(min = 1, max = 64) int dataFragments,
		@ForAll @IntRange(min = 1, max = 64) int parityFragments, @ForAll @IntRange(min = 1, max = 100) int threshold,
		@ForAll @IntRange(min = 0, max = 64) int lost) {

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
		final int trigger = Math.max(1, (parameters.layout().parityFragments()
			* parameters.getRepairThresholdPercent() + 99) / 100);

		storeSomething(fleet, random);

		final GroupId groupId = new ArrayList<>(fleet.groups()).get(random.nextInt(fleet.groups().size()));
		final List<Gridnode> holders = new ArrayList<>(holdersOf(fleet, groupId));
		final int wiped = random.nextInt(parameters.layout().parityFragments() + 1);

		Collections.shuffle(holders, random);
		holders.subList(0, wiped).forEach(g -> fleet.wipe(g.getId()));
		fleet.runRepairEpochs(2 * parameters.window());

		assertThat(fleet.holdersOf(groupId), equalTo((long) (wiped >= trigger ? guaranteed : guaranteed - wiped)));
	}

	@Property(tries = 30)
	public void neverRebuildsAGroupBeyondTheQuota(@ForAll("parametersWithoutExtras") StorageSpork.SporkData parameters,
		@ForAll long seed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window() + 4);
		final LayoutParameters layout = parameters.layout();
		final GroupId groupId = placeGroup(fleet, random).groupId();
		final List<Gridnode> holders = new ArrayList<>(holdersOf(fleet, groupId));
		final int fetches = fleet.getTransport().getFetches().get();

		Collections.shuffle(holders, random);
		holders.subList(0, layout.parityFragments()).forEach(g -> fleet.wipe(g.getId()));
		parameters.setMaxBytesPerNode((long) layout.maxFragments() * layout.getFragmentSize() - 1
			- random.nextInt(layout.getFragmentSize()));
		fleet.runRepairEpochs(2 * parameters.window());

		assertThat(fleet.getTransport().getFetches().get(), equalTo(fetches));
		assertThat(fleet.holdersOf(groupId), equalTo((long) layout.dataFragments()));
	}

	@Property(tries = 30)
	@SneakyThrows
	public void keepsRepairingPastAGroupItCannotRebuild(
		@ForAll("parametersWithoutExtras") StorageSpork.SporkData parameters, @ForAll long seed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window() + 4);
		final GroupKey mixed = placeGroup(fleet, random);
		final GroupId healthy = placeGroup(fleet, random).groupId();
		final List<Gridnode> holders = new ArrayList<>(holdersOf(fleet, healthy));

		fleet.replace(mixed.groupId(), ChunkGroups.seal(chunkOf(fleet, random), mixed, StorageFormat.current(),
			parameters.layout()), gridnode -> random.nextBoolean());

		for (Gridnode gridnode : holdersOf(fleet, mixed.groupId()).subList(0, parameters.layout().parityFragments())) {
			fleet.getStores().get(gridnode.getId()).remove(mixed.groupId());
		}

		Collections.shuffle(holders, random);
		holders.subList(0, parameters.layout().parityFragments()).forEach(g -> fleet.wipe(g.getId()));
		fleet.runRepairEpochs(2 * parameters.window());

		assertThat(fleet.holdersOf(healthy), equalTo((long) parameters.layout().guaranteedFragments()));
	}

	@Property(tries = 40)
	public void deletesOnlyOnTombstonesSignedByTheGroupKey(@ForAll("parameters") StorageSpork.SporkData parameters,
		@ForAll long seed, @ForAll Forgery forgery) {

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
		fleet.service(new SecureRandom()).delete(fingerprint);
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

	@Property(tries = 30)
	public void relocatesFragmentsIntoTheWindowAfterGridnodesJoin(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed,
		@ForAll @IntRange(min = 1, max = 3) int waves) throws Exception {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final Fingerprint fingerprint = storeSomething(fleet, random);

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

		fleet.service(new SecureRandom()).open(fingerprint);
	}
}
