/*
	Hedgehog
	Copyright © 2021-2026 The Unigrid Foundation, UGD Software AB

	This program is free software: you can redistribute it and/or modify it under the terms of the
	addended GNU Affero General Public License as published by the Free Software Foundation, version 3
	of the License (see COPYING and COPYING.addendum).

	This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
	the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General
	Public License for more details.

	You should have received an addended copy of the GNU Affero General Public License with this program.
	If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.service.storage;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import jakarta.enterprise.inject.spi.CDI;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.jboss.weld.environment.se.WeldContainer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.jqwik.NamedCDIProvider;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.GridnodeIdentity;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.producer.GridnodeProducer;
import org.unigrid.hedgehog.model.producer.StorageProducer;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.Fingerprint;
import org.unigrid.hedgehog.model.storage.FingerprintKeys;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.GroupKey;
import org.unigrid.hedgehog.model.storage.TestClock;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;
import org.unigrid.hedgehog.server.BaseServerTest;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.server.p2p.TopologyThread;

@WeldSetup({ TestServer.class, TopologyThread.class })
public class StorageNetworkTest extends BaseServerTest {
	private static final StorageSpork.SporkData PARAMETERS = StorageTestData.parameters();
	private static final TestClock CLOCK = new TestClock();

	private record Member(String container, Gridnode gridnode, Node client) { }

	private final List<Member> members = new ArrayList<>();
	private final Set<Node> reachable = ConcurrentHashMap.newKeySet();
	private StorageService service;

	@Override
	protected boolean isolateContainers() {
		return true;
	}

	@BeforeProperty
	private void mockPerNodeStorage() {
		new MockUp<GridnodeProducer>() {
			@Mock @SneakyThrows
			public GridnodeIdentity gridnodeIdentity() {
				return GridnodeIdentity.of(new Signature());
			}
		};

		new MockUp<StorageProducer>() {
			@Mock @SneakyThrows
			public FragmentStore fragmentStore() {
				return new FragmentStore(Jimfs.newFileSystem(Configuration.unix()).getPath("/fragments"), CLOCK);
			}

			@Mock
			public Optional<StorageSpork.SporkData> storageSpork() {
				return Optional.of(PARAMETERS);
			}

			@Mock
			public GroupRepairer groupRepairer(FragmentStore store, GridnodeDirectory directory,
				FragmentTransport transport) {

				return new GroupRepairer(store, directory, transport, () -> Optional.of(PARAMETERS), CLOCK);
			}
		};
	}

	@Provide
	Arbitrary<byte[]> files() {
		return StorageArbitraries.files(PARAMETERS);
	}

	private <T> T bean(String container, Class<T> type) {
		return WeldContainer.instance(container).select(type).get();
	}

	@SneakyThrows
	private void join() {
		if (!members.isEmpty()) {
			return;
		}

		for (int i = 0; i < servers().size(); i++) {
			final String container = containerName(i);
			final P2PServer p2p = servers().get(i).getP2p();
			final String address = p2p.getHostName() + ":" + p2p.getPort();
			final Gridnode gridnode = bean(container, GridnodeIdentity.class)
				.sign(Gridnode.Status.ACTIVE, address, System.currentTimeMillis(), 0);
			final Node client = Node.fromAddress(address);

			client.setConnection(Optional.of(new P2PClient(client.getAddress().getHostString(),
				client.getAddress().getPort())));
			members.add(new Member(container, gridnode, client));
		}

		members.forEach(member -> {
			reachable.add(member.client());
			meshInto(member);
		});

		final List<Gridnode> gridnodes = members.stream().map(Member::gridnode).toList();
		final PendingRequests pending = CDI.current().select(PendingRequests.class).get();

		service = new StorageService(new TopologyGridnodeDirectory(() -> gridnodes, () -> "client"),
			new NettyFragmentTransport(() -> reachable, pending, Optional::empty, StorageNetworkTest::noLocalKeeper),
			() -> Optional.of(PARAMETERS), new SecureRandom(), Duration.ZERO);
	}

	private static FragmentKeeper noLocalKeeper() {
		throw new IllegalStateException("The storage client is not a gridnode");
	}

	/* Every container learns all gridnodes and dials every other server, which is what repair travels over */
	private void meshInto(Member member) {
		NamedCDIProvider.within(member.container(), () -> {
			final Topology topology = bean(member.container(), Topology.class);
			final TopologyThread.NodeConnectionHandler dial = bean(member.container(), TopologyThread.class)
				.new NodeConnectionHandler(topology);

			members.forEach(other -> topology.offerGridnode(other.gridnode()));
			members.stream().filter(other -> other != member).forEach(other -> {
				final Node peer = Node.builder().address(other.client().getAddress()).build();

				if (topology.addNode(peer)) {
					dial.accept(peer);
				}
			});
		});
	}

	@SneakyThrows
	private Fingerprint store(byte[] file) {
		return service.store(new ByteArrayInputStream(file));
	}

	@SneakyThrows
	private byte[] retrieve(Fingerprint fingerprint) {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();

		service.retrieve(fingerprint, output);
		return output.toByteArray();
	}

	private Set<GroupId> groups() {
		return members.stream().flatMap(member -> bean(member.container(), FragmentStore.class).groups().stream())
			.collect(Collectors.toSet());
	}

	private long holders() {
		return members.stream().filter(member -> !bean(member.container(), FragmentStore.class).groups().isEmpty())
			.count();
	}

	private List<Member> holdersOf(GroupId groupId) {
		return members.stream().filter(member -> bean(member.container(), FragmentStore.class).holding(groupId)
			.isPresent()).toList();
	}

	private List<Member> shuffled(long seed) {
		final List<Member> shuffled = new ArrayList<>(members);

		Collections.shuffle(shuffled, new Random(seed));
		return shuffled;
	}

	private void withLost(List<Member> lost, Runnable action) {
		lost.forEach(member -> reachable.remove(member.client()));

		try {
			action.run();
		} finally {
			lost.forEach(member -> reachable.add(member.client()));
		}
	}

	@SneakyThrows
	private void wipe(Member member) {
		final FragmentStore store = bean(member.container(), FragmentStore.class);

		for (GroupId groupId : store.groups()) {
			store.remove(groupId);
		}
	}

	private void runRepairEpochs(int epochs) {
		for (int epoch = 0; epoch < epochs; epoch++) {
			CLOCK.advance(Duration.ofMinutes(PARAMETERS.getRepairIntervalMinutes()));
			members.forEach(member -> NamedCDIProvider.within(member.container(),
				() -> bean(member.container(), GroupRepairer.class).runEpoch()));
		}
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void readsBackStoredFileAcrossTheNetwork(@ForAll("files") byte[] file) {
		join();

		assertThat(retrieve(store(file)), equalTo(file));
		assertThat(holders(), greaterThanOrEqualTo((long) PARAMETERS.layout().guaranteedFragments()));
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void readsBackWithGridnodesUnreachable(@ForAll("files") byte[] file, @ForAll long seed) {
		join();

		final Fingerprint fingerprint = store(file);

		withLost(shuffled(seed).subList(0, PARAMETERS.layout().parityFragments()),
			() -> assertThat(retrieve(fingerprint), equalTo(file)));
	}

	@Property(tries = 2, shrinking = ShrinkingMode.OFF)
	public void restoresFragmentsOfWipedGridnodes(@ForAll("files") byte[] file) {
		join();

		final int parity = PARAMETERS.layout().parityFragments();
		final Fingerprint fingerprint = store(file);
		final GroupId manifest = new GroupKey(new FingerprintKeys(fingerprint).manifestSeed(0)).groupId();

		final List<Member> holders = holdersOf(manifest);

		holders.subList(0, holders.size() - PARAMETERS.layout().dataFragments()).forEach(this::wipe);
		runRepairEpochs(PARAMETERS.window());

		assertThat(holdersOf(manifest), hasSize(greaterThanOrEqualTo(PARAMETERS.layout().guaranteedFragments())));
		withLost(holdersOf(manifest).subList(0, parity), () -> assertThat(retrieve(fingerprint), equalTo(file)));
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void deleteRemovesEveryFragment(@ForAll("files") byte[] file) throws StorageException {
		join();

		final Set<GroupId> before = groups();
		final Fingerprint fingerprint = store(file);
		final Set<GroupId> stored = groups();

		stored.removeAll(before);
		assertThat(stored, is(not(empty())));

		service.delete(fingerprint);

		assertThat(groups(), equalTo(before));
		assertThrows(FingerprintNotFoundException.class, () -> service.open(fingerprint));
	}
}
