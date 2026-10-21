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

package org.unigrid.hedgehog.service.ledger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import mockit.Mock;
import mockit.MockUp;
import org.apache.commons.lang3.Validate;
import org.jboss.weld.environment.se.WeldContainer;
import static org.awaitility.Awaitility.await;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.ledger.Address;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.spork.ChunkData;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.server.rest.RestServer;
import static org.unigrid.hedgehog.service.ledger.FleetContainers.bean;
import static org.unigrid.hedgehog.service.ledger.FleetContainers.within;
import org.unigrid.hedgehog.service.ledger.LedgerService.Setup;

/* Real daemons in one process: each node is a container of its own with a QUIC server, the streams it has to the
   others, and a LedgerService that the container makes and starts as the daemon does. They agree on one genesis, built
   from the keys of the validators and the one bootstrap snapshot that all of them read from the data directory of the
   mock-up (FleetChain). The files of a node, its block log, its sign state and its key, are on a virtual file system
   of the fleet, one directory for each node, and survive a stop of the node: restart() makes a new service over the
   same files. A virtual file system gives every lock to whoever asks, so the lock of a sign state is not tested.

   What is faked is the surroundings of the service and nothing in it: LedgerService.setup() answers with the
   directory, the key file, the genesis, the clock and the speed of the node, and the network keys are the two of the
   fleet, which is how a test signs a spork. Nodes are numbered from 0: the validators first, then the observers in the
   order they were made. Every node is one of the 20 servers of BaseServerTest, so a fleet is at most 20 nodes. The
   fakes end with a test, and mockEnvironment() makes them again before every try. */
@Slf4j
public final class LedgerFleet implements AutoCloseable {
	/* A tick every 200 ms, a heartbeat and a status every second, and a grace period of a second and a half before
	   a node with a key votes: the nodes of a fleet are on one host and the time is real */
	public static final DriverOptions FAST = new DriverOptions(200, 1_000, 1_000, 1_500, 10_000, 10_000, 100,
		Optional.empty());

	public static final long COIN = 100_000_000L;
	private static final Map<String, Setup> SETUPS = new ConcurrentHashMap<>();
	private static final String LOCAL = "127.0.0.1";
	private static final long POLL_MILLIS = 100;
	private static final long SUBMIT_SECONDS = 10;
	private static final int DIAL_ATTEMPTS = 3;

	/* A service that refuses to start, for a key file that others can read, say, logs why and stays inactive for good:
	   it is not waited for as long as the network is */
	private static final Duration START_WITHIN = Duration.ofSeconds(30);

	@Value
	@Builder(toBuilder = true)
	public static class Settings {
		@Builder.Default int validators = 4;
		@Builder.Default int observers = 1;
		/* One legacy wallet for each entry, holding that many units of the snapshot */
		@Builder.Default List<Long> balances = Collections.nCopies(8, 50 * COIN);
		@Builder.Default int tipHeight = 1_000;
		@Builder.Default Duration tipAge = Duration.ofHours(1);
		@Builder.Default DriverOptions options = FAST;
	}

	private record FleetNode(int index, String container, byte[] seed, Setup setup) {
	}

	private record Dial(FleetNode from, P2PClient client, Node added) {
	}

	private final Settings settings;
	private final List<TestServer> servers;
	private final IntFunction<String> containers;
	private final FleetClock clock = new FleetClock();
	private final FileSystem files = LedgerKeyFixtures.posixFileSystem();
	private final FleetChain chain;
	private final Path genesisFile = files.getPath("/genesis.json");
	private final List<FleetNode> nodes = new CopyOnWriteArrayList<>();
	private final List<Dial> dials = new CopyOnWriteArrayList<>();
	private final Set<Integer> stopped = ConcurrentHashMap.newKeySet();
	private final FleetSporks sporks = new FleetSporks(index -> node(index).container(), nodes::size);
	private final FleetRest rest;

	@SneakyThrows
	private LedgerFleet(final Settings settings, final List<TestServer> servers, final IntFunction<String> containers) {
		this.settings = settings;
		this.servers = servers;
		this.containers = containers;
		this.rest = new FleetRest(index -> servers.get(index).getRest());

		final List<byte[]> seeds = IntStream.range(0, settings.getValidators()).mapToObj(this::newSeed).toList();

		chain = FleetChain.of(settings, clock, seeds);
		GenesisFile.write(genesisFile, chain.genesis());
		IntStream.range(0, settings.getValidators()).forEach(n -> nodes.add(node(n, seeds.get(n))));
		IntStream.range(0, settings.getObservers()).forEach(n -> nodes.add(node(nodes.size(), null)));
	}

	/* The key file is made as the command of the operator makes it, and read back as the service reads it */
	@SneakyThrows
	private byte[] newSeed(final int index) {
		LedgerKeyFile.create(keyFile(index));
		return LedgerKeyFile.load(keyFile(index));
	}

	private Path keyFile(final int index) {
		return files.getPath("/node-" + index, "ledger.key");
	}

	private FleetNode node(final int index, final byte[] seed) {
		final Setup setup = new Setup(files.getPath("/node-" + index, "ledger"),
			Optional.ofNullable(seed).map(key -> keyFile(index)), Optional.of(genesisFile), clock,
			settings.getOptions(), POLL_MILLIS);

		return new FleetNode(index, containers.apply(index), seed, setup);
	}

	/* Starts the nodes and meshes them; a fleet that cannot be started is closed, so that its nodes do not live on in
	   the process of the tests */
	public static LedgerFleet start(final Settings settings, final List<TestServer> servers,
		final IntFunction<String> containers) {

		final LedgerFleet fleet = new LedgerFleet(settings, servers, containers);

		try {
			fleet.boot();
		} catch (RuntimeException | Error ex) {
			fleet.close();
			throw ex;
		}

		return fleet;
	}

	/* The fakes of a test: the network keys are the two of the fleet, and the service gets its surroundings from the
	   fleet that owns its container */
	public static void mockEnvironment() {
		FleetSporks.mockNetworkKeys();

		new MockUp<LedgerService>() {
			@Mock
			Setup setup() {
				final String container = ((WeldContainer) CDI.current()).getId();

				return Optional.ofNullable(SETUPS.get(container)).orElseThrow(() ->
					new IllegalStateException(container + " is not a node of a fleet"));
			}
		};
	}

	private void boot() {
		nodes.forEach(this::begin);
		nodes.forEach(this::awaitRunning);
		nodes.forEach(node -> Validate.isTrue(service(node).supply().orElse(-1) == snapshotTotal(),
			"The node %d did not start on the snapshot of the genesis", node.index()));
		mesh(nodes, nodes);
		awaitMeshed();
	}

	private void begin(final FleetNode node) {
		SETUPS.put(node.container(), node.setup());
		within(node.container(), () -> service(node).status());
	}

	private void awaitRunning(final FleetNode node) {
		await("node " + node.index() + " running with a ledger").atMost(START_WITHIN).until(() ->
			service(node).status().running() && service(node).height().isPresent());
	}

	/* Every node dials every other, as the nodes of one host do, so a pair has two streams and is one peer */
	private void mesh(final List<FleetNode> dialing, final List<FleetNode> dialed) {
		dialing.forEach(from -> dialed.stream().filter(to -> to != from).forEach(to -> dial(from, to)));
	}

	/* A peer is listed only for a stream whose connection settled on the ledger protocol, so a full list proves that
	   the protocol was negotiated between every two nodes */
	private void awaitMeshed() {
		await("every node has every other as a peer of the ledger").until(() ->
			nodes.stream().allMatch(node -> ledgerPeers(node.index()).size() == nodes.size() - 1));
	}

	private void dial(final FleetNode from, final FleetNode to) {
		within(from.container(), () -> {
			final Topology topology = bean(from.container(), Topology.class);
			final Node node = listenNode(to);
			final P2PClient client = client(to);
			final boolean added = topology.addNode(node);

			if (added) {
				topology.modifyNode(node, held -> held.setConnection(Optional.of(client)));
			}

			dials.add(new Dial(from, client, added ? node : null));
			return null;
		});
	}

	/* A handshake that takes longer than the client waits for it, as one does on a host that is busy, is tried again */
	@SneakyThrows
	private P2PClient client(final FleetNode to) {
		final P2PServer server = servers.get(to.index()).getP2p();

		for (int attempt = 1; ; attempt++) {
			try {
				return new P2PClient(server.getHostName(), server.getPort());
			} catch (TimeoutException ex) {
				if (attempt == DIAL_ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	@SneakyThrows
	private Node listenNode(final FleetNode node) {
		return Node.fromAddress(LOCAL + ":" + servers.get(node.index()).getP2p().getPort());
	}

	private void hangUp(final Dial dial) {
		within(dial.from().container(), () -> {
			dial.client().closeDirty();

			if (dial.added() != null) {
				bean(dial.from().container(), Topology.class).removeNode(dial.added());
			}

			return null;
		});
	}

	private LedgerService service(final FleetNode node) {
		Validate.isTrue(!stopped.contains(node.index()), "The node %d is stopped", node.index());
		return bean(node.container(), LedgerService.class);
	}

	private FleetNode node(final int index) {
		return nodes.get(index);
	}

	private Stream<FleetNode> running() {
		return nodes.stream().filter(node -> !stopped.contains(node.index()));
	}

	/* The nodes */

	public int size() {
		return nodes.size();
	}

	public int validators() {
		return (int) nodes.stream().filter(node -> node.seed() != null).count();
	}

	public boolean isRunning(final int node) {
		return !stopped.contains(node);
	}

	public FleetClock clock() {
		return clock;
	}

	public LedgerGenesis genesis() {
		return chain.genesis();
	}

	public byte[] chainId() {
		return chain.genesis().chainId();
	}

	/* The seed of a validator: for a test that must act as one */
	public byte[] seed(final int validator) {
		return Objects.requireNonNull(node(validator).seed(), "The node " + validator + " is an observer").clone();
	}

	/* Where the node keeps its block log and its sign state, on the virtual file system of the fleet */
	public Path directoryOf(final int node) {
		return node(node).setup().directory().resolve(LedgerService.chainDirectoryOf(chain.genesis()));
	}

	public LegacyWallet wallet(final int n) {
		return chain.wallets().get(n);
	}

	/* What the snapshot holds at the legacy address of the wallet, in units */
	public long holding(final int wallet) {
		return settings.getBalances().get(wallet);
	}

	public long snapshotTotal() {
		return chain.genesis().getSnapshotTotal();
	}

	/* The tip of the snapshot: the height of the old chain and the time of its block, in Unix milliseconds */
	public int tipHeight() {
		return settings.getTipHeight();
	}

	public long tipTimeMillis() {
		return chain.tipTimeMillis();
	}

	/* The ids of the peers of the ledger of a node, which are the other nodes by the port each listens on */
	public List<String> ledgerPeers(final int node) {
		return gatewayOf(node).network().peers().stream().map(LedgerPeer::id).toList();
	}

	/* Where the node hears its peers: a test can speak to the driver of the node as a peer does */
	public LedgerGateway gatewayOf(final int node) {
		return bean(node(node).container(), LedgerGateway.class);
	}

	/* What a node answers about itself */

	public LedgerService.Report status(final int node) {
		return service(node(node)).status();
	}

	public long height(final int node) {
		return service(node(node)).height().orElseThrow(() -> noLedger(node));
	}

	public long supplyOn(final int node) {
		return service(node(node)).supply().orElseThrow(() -> noLedger(node));
	}

	public long balanceOf(final int node, final Address address) {
		return service(node(node)).balanceOf(address).orElseThrow(() -> noLedger(node));
	}

	private static AssertionError noLedger(final int node) {
		return new AssertionError("The node " + node + " has no ledger yet");
	}

	/* The answer of the ledger of the node: empty when the transaction was taken, else why not */
	@SneakyThrows
	public Optional<String> submit(final int node, final Transaction transaction) {
		return service(node(node)).submit(transaction).get(SUBMIT_SECONDS, TimeUnit.SECONDS);
	}

	/* Every node that runs holds a ledger of at least this height */
	public void awaitHeight(final long height) {
		await("every node at height " + height).until(() ->
			running().allMatch(node -> service(node).height().orElse(-1) >= height));
	}

	public void awaitHeight(final int node, final long height) {
		await("node " + node + " at height " + height).until(() ->
			service(node(node)).height().orElse(-1) >= height);
	}

	/* Every node that runs is at the same height with the same tip, which is when none of them is behind */
	public void awaitSameChain() {
		await("every node on the same chain").until(() -> running().map(node -> service(node).status())
			.map(report -> report.height() + ":" + report.tipHash()).distinct().count() == 1);
	}

	/* Stopping and starting the service of a node */

	/* The container ends the service as it does when the daemon shuts down, and makes a new one the next time it is
	   asked for. The node is then left alone until restart(), as a call to its service would make a new one. */
	public void stop(final int node) {
		final FleetNode stopping = node(node);
		final LedgerService ending = CDIUtil.unproxy(service(stopping));

		within(stopping.container(), () -> {
			final BeanManager manager = WeldContainer.instance(stopping.container()).getBeanManager();

			((AlterableContext) manager.getContext(ApplicationScoped.class))
				.destroy(manager.resolve(manager.getBeans(LedgerService.class)));
			return null;
		});
		stopped.add(node);
		Validate.isTrue(!ending.status().running(), "The service of the node %d is still running", node);
	}

	/* A new service over the files of the node. Its peers are announced to it the way the status schedule of the
	   server does every ten seconds, so that it does not have to be waited for. */
	public void restart(final int node) {
		final FleetNode starting = node(node);

		Validate.isTrue(stopped.remove(node), "The node %d is running", node);
		begin(starting);
		awaitRunning(starting);
		gatewayOf(node).network().peers().forEach(peer -> gatewayOf(node).peerConnected((NettyLedgerPeer) peer));
	}

	/* A node without a key that starts with no blocks, which is how a node joins; its index. The sporks it is given are
	   stored before its service begins, so the node has them when it first asks its peers for blocks. */
	public int addObserver(final GridSpork... held) {
		Validate.isTrue(nodes.size() < servers.size(), "A fleet is at most %d nodes", servers.size());

		final FleetNode observer = node(nodes.size(), null);
		final List<FleetNode> others = List.copyOf(nodes);

		nodes.add(observer);
		Stream.of(held).forEach(spork -> deliverSpork(observer.index(), spork));
		begin(observer);
		awaitRunning(observer);
		mesh(List.of(observer), others);
		mesh(others, List.of(observer));
		awaitMeshed();
		return observer.index();
	}

	/* Sporks: signed by the two network keys of the fleet, and delivered to the nodes through the receive path of
	   a node that hears a spork */

	public GridSpork sporkOn(final int node, final Type type) {
		return sporks.stored(node, type);
	}

	/* The next version of the spork with this data, signed once: a proposal, which a node holds until a second
	   key signs, and which makes the type count as out of date on the node until a spork signed twice is newer */
	public GridSpork proposal(final Type type, final ChunkData data) {
		return sporks.proposal(type, data);
	}

	public GridSpork signed(final Type type, final ChunkData data) {
		return sporks.signed(type, data);
	}

	/* The next version of the spork with this data to every node, and until each of them holds it; gives the spork */
	public GridSpork publishSpork(final Type type, final ChunkData data) {
		final GridSpork spork = signed(type, data);

		publishSpork(spork);
		return spork;
	}

	public void publishSpork(final GridSpork spork) {
		sporks.publish(spork);
	}

	/* To one node alone, whether or not it takes it */
	public void deliverSpork(final int node, final GridSpork spork) {
		sporks.deliver(node, spork);
	}

	/* The real REST server of the container at an index of the servers: that of a node, or that of a container no node
	   was made in, which runs no ledger */
	public RestServer restOf(final int index) {
		return servers.get(index).getRest();
	}

	/* The command of the foundation, to the real REST server of one node: the data is proposed at the location with
	   the first network key, and the node spreads the proposal to its peers. Gives the digest to co-sign by. */
	public String proposeByRest(final int node, final String location, final Object data) {
		return rest.propose(node, location, data);
	}

	/* The co-signature with the second key, after which the node stores the spork and spreads it */
	public void cosignByRest(final int node, final String digest) {
		rest.cosign(node, digest);
	}

	/* The services stop, the streams close and the files of the fleet go. The containers stay, as the containers of
	   a test do. */
	@Override
	public void close() {
		dials.forEach(this::hangUp);
		dials.clear();
		nodes.reversed().stream().filter(node -> !stopped.contains(node.index())).forEach(this::quietly);
		nodes.forEach(node -> SETUPS.remove(node.container()));
		nodes.forEach(node -> within(node.container(), () -> {
			bean(node.container(), Topology.class).clear();
			return null;
		}));
		release();
	}

	private void quietly(final FleetNode node) {
		try {
			stop(node.index());
		} catch (RuntimeException ex) {
			log.atWarn().setCause(ex).log("Could not stop the node {}", node.index());
		}
	}

	@SneakyThrows
	private void release() {
		Files.deleteIfExists(chain.snapshot());
		files.close();
	}
}
