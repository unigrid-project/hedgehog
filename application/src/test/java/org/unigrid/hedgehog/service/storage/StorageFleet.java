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

package org.unigrid.hedgehog.service.storage;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.TestClock;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.model.storage.placement.Placement;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;

@Getter
public class StorageFleet {
	private final List<Gridnode> gridnodes = new CopyOnWriteArrayList<>();
	private final Map<String, FragmentStore> stores = new ConcurrentHashMap<>();
	private final InMemoryTransport transport = new InMemoryTransport();
	private final TestClock clock = new TestClock();
	@Setter private StorageSpork.SporkData parameters;

	public StorageFleet(StorageSpork.SporkData parameters, int size) {
		this.parameters = parameters;
		IntStream.range(0, size).forEach(i -> join());
	}

	public Gridnode join() {
		final int number = gridnodes.size();
		final Gridnode gridnode = Gridnode.builder().id("gridnode-" + number)
			.hostName("10.1." + number / 256 + "." + number % 256 + ":52883").status(Gridnode.Status.ACTIVE).build();

		wipe(gridnode.getId());
		gridnodes.add(gridnode);
		return gridnode;
	}

	@SneakyThrows
	public void wipe(String id) {
		final FragmentStore store = new FragmentStore(Jimfs.newFileSystem(Configuration.unix()).getPath("/fragments"),
			clock);

		stores.put(id, store);
		transport.attach(id, new FragmentKeeper(store, this::spork, () -> Optional.of(id)));
	}

	public List<Gridnode> online() {
		return gridnodes.stream().filter(gridnode -> transport.isOnline(gridnode.getId())).collect(Collectors.toList());
	}

	public Optional<StorageSpork.SporkData> spork() {
		return Optional.of(parameters);
	}

	public GridnodeDirectory directory(String self) {
		return new TopologyGridnodeDirectory(() -> gridnodes, () -> self);
	}

	public StorageService service(final SecureRandom random) {
		return new StorageService(directory("client"), transport, this::spork, random, Duration.ZERO);
	}

	/* Seeded from the fleet's own clock, so a failing sample replays with the same spot checks */
	public GroupRepairer repairer(String id) {
		return new GroupRepairer(stores.get(id), directory(id), transport, this::spork, clock,
			new Random(clock.millis() ^ id.hashCode()));
	}

	public void runRepairEpochs(int epochs) {
		for (int epoch = 0; epoch < epochs; epoch++) {
			clock.advance(Duration.ofMinutes(parameters.getRepairIntervalMinutes()));
			online().forEach(gridnode -> repairer(gridnode.getId()).runEpoch());
		}
	}

	@SneakyThrows
	public void forget(final GroupId groupId) {
		for (final FragmentStore store : stores.values()) {
			store.remove(groupId);
		}
	}

	/* Swaps the fragment a chosen holder keeps for the one at the same index of another seal of the group */
	@SneakyThrows
	public void replace(final GroupId groupId, final List<Fragment> sealed, final Predicate<Gridnode> chosen) {
		for (final Gridnode gridnode : gridnodes.stream().filter(chosen).collect(Collectors.toList())) {
			final FragmentStore store = stores.get(gridnode.getId());
			final Optional<FragmentStore.Holding> holding = store.holding(groupId);

			if (holding.isPresent()) {
				store.remove(groupId);
				new FragmentKeeper(store, this::spork, () -> Optional.of(gridnode.getId()))
					.store(sealed.get(holding.get().getIndex()).encode());
			}
		}
	}

	public long holdersOf(GroupId groupId) {
		return online().stream().filter(gridnode -> stores.get(gridnode.getId()).holding(groupId).isPresent()).count();
	}

	/* Duplicates and copies outside the window never help a retrieval, so only distinct indices there count */
	public long distinctIndicesOf(GroupId groupId, int width) {
		return Placement.window(groupId, gridnodes, width).stream().filter(g -> transport.isOnline(g.getId()))
			.map(g -> stores.get(g.getId()).holding(groupId)).flatMap(Optional::stream)
			.map(FragmentStore.Holding::getIndex).distinct().count();
	}

	public Set<GroupId> groups() {
		return stores.values().stream().flatMap(store -> store.groups().stream()).collect(Collectors.toSet());
	}
}
