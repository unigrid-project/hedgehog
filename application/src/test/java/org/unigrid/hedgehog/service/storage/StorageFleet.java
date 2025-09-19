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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.TestClock;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
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
		transport.attach(id, new FragmentKeeper(store, this::spork));
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

	public StorageService service(SecureRandom random) {
		return new StorageService(directory("client"), transport, this::spork, random, Duration.ZERO);
	}

	public long holdersOf(GroupId groupId) {
		return online().stream().filter(gridnode -> stores.get(gridnode.getId()).holding(groupId).isPresent()).count();
	}

	public Set<GroupId> groups() {
		return stores.values().stream().flatMap(store -> store.groups().stream()).collect(Collectors.toSet());
	}
}
