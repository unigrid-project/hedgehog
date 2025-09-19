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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.ChunkGroups;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.placement.Placement;

public class GroupDistributorTest {
	@Provide
	Arbitrary<StorageSpork.SporkData> parameters() {
		return StorageArbitraries.parameters();
	}

	private static List<Fragment> seal(StorageSpork.SporkData parameters, GroupKey key, Random random) {
		final byte[] chunk = new byte[parameters.getChunkSize()];
		random.nextBytes(chunk);
		return ChunkGroups.seal(chunk, key, StorageFormat.current(), parameters.layout());
	}

	private static Set<Integer> heldIndices(StorageFleet fleet, GroupKey key) {
		return fleet.online().stream().map(g -> fleet.getStores().get(g.getId()).holding(key.groupId()))
			.filter(Optional::isPresent).map(h -> h.get().getIndex()).collect(Collectors.toSet());
	}

	@Property(tries = 60)
	public void placesEveryGuaranteedFragmentDespiteOutagesWithinTheSlack(
		@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed,
		@ForAll @IntRange(min = 0, max = 300) int outageSeed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window() + random.nextInt(5));
		final GroupKey key = StorageTestData.key(random);
		final List<Gridnode> window = Placement.window(key.groupId(), fleet.getGridnodes(), parameters.window());
		final int spare = parameters.window() - parameters.layout().guaranteedFragments();
		final List<Gridnode> shuffled = new ArrayList<>(window);

		Collections.shuffle(shuffled, random);
		shuffled.subList(0, outageSeed % (spare + 1)).forEach(g -> fleet.getTransport().offline(g.getId()));

		final boolean placed = new GroupDistributor(fleet.getTransport(), random, Duration.ZERO)
			.place(seal(parameters, key, random), window);
		final List<Integer> guaranteed = IntStream.range(0, parameters.layout().guaranteedFragments()).boxed()
			.collect(Collectors.toList());

		assertThat(placed, is(true));
		assertThat(heldIndices(fleet, key), hasItems(guaranteed.toArray(new Integer[0])));
		assertThat(fleet.holdersOf(key.groupId()), equalTo((long) heldIndices(fleet, key).size()));
		fleet.getGridnodes().stream().filter(g -> !window.contains(g)).forEach(g -> assertThat(fleet.getStores()
			.get(g.getId()).holding(key.groupId()).isPresent(), is(false)));
	}

	@Property(tries = 20)
	public void placesTheGuaranteedFragmentsWithJitter(@ForAll("parameters") StorageSpork.SporkData parameters,
		@ForAll long seed, @ForAll @IntRange(min = 1, max = 3) int jitterMillis) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final GroupKey key = StorageTestData.key(random);
		final List<Gridnode> window = Placement.window(key.groupId(), fleet.getGridnodes(), parameters.window());
		final List<Integer> guaranteed = IntStream.range(0, parameters.layout().guaranteedFragments()).boxed()
			.collect(Collectors.toList());

		assertThat(new GroupDistributor(fleet.getTransport(), random, Duration.ofMillis(jitterMillis))
			.place(seal(parameters, key, random), window), is(true));
		assertThat(heldIndices(fleet, key), hasItems(guaranteed.toArray(new Integer[0])));
	}

	@Property(tries = 40)
	public void neverWaitsForTheExtraFragments(@ForAll("parameters") StorageSpork.SporkData parameters,
		@ForAll long seed) throws Exception {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final GroupKey key = StorageTestData.key(random);
		final List<Gridnode> window = Placement.window(key.groupId(), fleet.getGridnodes(), parameters.window());
		final GroupDistributor distributor = new GroupDistributor(fleet.getTransport(), random, Duration.ZERO);
		final List<Fragment> sealed = seal(parameters, key, random);

		window.subList(parameters.layout().guaranteedFragments(), window.size())
			.forEach(g -> fleet.getTransport().silence(g.getId()));

		assertThat(CompletableFuture.supplyAsync(() -> distributor.place(sealed, window)).get(10, TimeUnit.SECONDS),
			is(true));
	}

	@Property
	public void acceptsOnlyAJitterItCanDraw(@ForAll long jitterMillis) {
		final Duration jitter = Duration.ofMillis(jitterMillis);

		if (jitterMillis < 0 || jitterMillis >= Integer.MAX_VALUE) {
			assertThrows(IllegalArgumentException.class, () -> new GroupDistributor(new InMemoryTransport(),
				new Random(), jitter));
		} else {
			new GroupDistributor(new InMemoryTransport(), new Random(), jitter);
		}
	}

	@Property(tries = 40)
	public void refusesWhenTooManyAreDown(@ForAll("parameters") StorageSpork.SporkData parameters, @ForAll long seed) {
		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final GroupKey key = StorageTestData.key(random);
		final List<Gridnode> window = Placement.window(key.groupId(), fleet.getGridnodes(), parameters.window());
		final int spare = parameters.window() - parameters.layout().guaranteedFragments();

		window.subList(0, spare + 1).forEach(g -> fleet.getTransport().offline(g.getId()));

		assertThat(new GroupDistributor(fleet.getTransport(), random, Duration.ZERO)
			.place(seal(parameters, key, random), window), is(false));
	}

	@Property(tries = 40)
	public void withdrawTombstonesEveryReachableWindowMember(@ForAll("parameters") StorageSpork.SporkData parameters,
		@ForAll long seed) {

		final Random random = new Random(seed);
		final StorageFleet fleet = new StorageFleet(parameters, parameters.window());
		final GroupKey key = StorageTestData.key(random);
		final List<Gridnode> window = Placement.window(key.groupId(), fleet.getGridnodes(), parameters.window());
		final GroupDistributor distributor = new GroupDistributor(fleet.getTransport(), random, Duration.ZERO);
		final Set<Gridnode> offline = window.stream().filter(g -> random.nextInt(3) == 0).collect(Collectors.toSet());

		distributor.place(seal(parameters, key, random), window);
		offline.forEach(g -> fleet.getTransport().offline(g.getId()));
		distributor.withdraw(key, window, 42);

		assertThat(fleet.holdersOf(key.groupId()), equalTo(0L));
		window.forEach(g -> assertThat(fleet.getStores().get(g.getId()).isTombstoned(key.groupId()),
			is(!offline.contains(g))));
	}
}
