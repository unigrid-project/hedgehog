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

import jakarta.enterprise.inject.spi.CDI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.SneakyThrows;
import mockit.Delegate;
import mockit.Expectations;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.jboss.weld.environment.se.WeldContainer;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.jqwik.NamedCDIProvider;
import org.unigrid.hedgehog.ledger.Block;
import org.unigrid.hedgehog.ledger.LedgerDriver.Checkpoint;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.server.BaseServerTest;
import org.unigrid.hedgehog.server.p2p.P2PServer;

/* Ledger nodes on real QUIC servers: each node is a container with its own server and gateway, and a driver over a
   ledger in memory. The nodes dial each other as they do on one host, and every one of them has a port of its own. */
public class LedgerSyncNetworkTest extends BaseServerTest {
	private static final int BLOCKS = 20;
	private static final int CHECKPOINT_HEIGHT = 10;
	private static final String LOCAL = "127.0.0.1";

	/* Short intervals, as the time is real: a tick every 200 ms, a heartbeat and a status every second, which is what a
	   peer takes from another, and a grace period of a second and a half before a node with a key votes */
	private static final DriverOptions OPTIONS = new DriverOptions(200, 1_000, 1_000, 1_500, 10_000, 10_000, 100,
		Optional.empty());

	private static final List<Block> CHAIN = LedgerTestChain.blocks(BLOCKS);

	/* A node of the network: its server, the gateway that hands the driver what the server hears, and the driver */
	private record Station(int index, String container, LedgerGateway gateway, RecordingNetwork network,
		LedgerTestNode node, String listenId) {
	}

	private record Dial(Station from, P2PClient client, Node added) {
	}

	private final Map<String, Integer> ports = new HashMap<>();
	private final List<Station> stations = new ArrayList<>();
	private final List<Dial> dials = new ArrayList<>();

	@Override
	protected boolean isolateContainers() {
		return true;
	}

	/* The servers of a test share one process and so one NetOptions, while each node of a network has its own port
	   and says it in its hello. Each container is given the port of its own server. */
	@BeforeTry
	private void giveEachContainerItsOwnPort() {
		for (int i = 0; i < servers().size(); i++) {
			ports.put(containerName(i), servers().get(i).getP2p().getPort());
		}

		new Expectations() {{
			NetOptions.getPort(); minTimes = 0;
			result = new Delegate<Integer>() {
				int port() {
					return ports.get(((WeldContainer) CDI.current()).getId());
				}
			};
		}};
	}

	@AfterTry
	private void shutDown() {
		dials.forEach(this::hangUp);
		dials.clear();
		stations.reversed().forEach(this::stop);
		stations.clear();
	}

	private <T> T within(final String container, final Supplier<T> action) {
		final AtomicReference<T> result = new AtomicReference<>();

		NamedCDIProvider.within(container, () -> result.set(action.get()));
		return result.get();
	}

	private <T> T bean(final String container, final Class<T> type) {
		return WeldContainer.instance(container).select(type).get();
	}

	private Station start(final int index, final byte[] seed, final DriverOptions options, final List<Block> blocks) {
		final String container = containerName(index);

		return within(container, () -> {
			final LedgerGateway gateway = bean(container, LedgerGateway.class);
			final RecordingNetwork network = new RecordingNetwork(gateway.network());
			final LedgerTestNode node = new LedgerTestNode(container, network, seed, options, blocks);
			final Station station = new Station(index, container, gateway, network, node,
				LOCAL + ":" + servers().get(index).getP2p().getPort());

			stations.add(station);
			gateway.attach(node.driver());
			node.driver().start();
			return station;
		});
	}

	private void stop(final Station station) {
		within(station.container(), () -> {
			station.gateway().detach();
			station.node().close();
			bean(station.container(), Topology.class).clear();
			return null;
		});
	}

	/* The way a node dials another: the topology holds the node with the stream. A node that dialled this one is in the
	   topology already, with the stream it opened, and then the new stream is the second one between the two, as it
	   is when both dial at once. */
	@SneakyThrows
	private Dial connect(final Station from, final Station to) {
		return within(from.container(), () -> {
			final Topology topology = bean(from.container(), Topology.class);
			final Node node = node(to);
			final P2PClient client = client(to);
			final boolean added = topology.addNode(node);
			final Dial dial = new Dial(from, client, added ? node : null);

			if (added) {
				topology.modifyNode(node, held -> held.setConnection(Optional.of(client)));
			}

			dials.add(dial);
			return dial;
		});
	}

	@SneakyThrows
	private P2PClient client(final Station to) {
		final P2PServer server = servers().get(to.index()).getP2p();

		return new P2PClient(server.getHostName(), server.getPort());
	}

	@SneakyThrows
	private static Node node(final Station station) {
		return Node.fromAddress(station.listenId());
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

	private static List<String> ids(final Station station) {
		return station.gateway().network().peers().stream().map(LedgerPeer::id).toList();
	}

	private static List<byte[]> hashesOf(final LedgerTestNode node) {
		final List<Block> held = new ArrayList<>(node.ledger().blocksFrom(1, 16));

		held.addAll(node.ledger().blocksFrom(17, 16));
		return held.stream().map(Block::hash).toList();
	}

	private static List<String> hexes(final List<byte[]> hashes) {
		return hashes.stream().map(HexFormat.of()::formatHex).toList();
	}

	private static DriverOptions trusting(final Checkpoint checkpoint) {
		return new DriverOptions(OPTIONS.tickMillis(), OPTIONS.heartbeatMillis(), OPTIONS.statusMillis(),
			OPTIONS.graceMillis(), OPTIONS.restartWindowMillis(), OPTIONS.maxQueuedInbound(),
			OPTIONS.maxQueuedPerPeer(), Optional.of(checkpoint));
	}

	private void assertHoldsTheChain(final Station station) {
		assertThat(hexes(hashesOf(station.node())), equalTo(hexes(CHAIN.stream().map(Block::hash).toList())));
		assertThat(station.node().ledger().tipHash(), equalTo(LedgerTestChain.tipOf(CHAIN)));
	}

	private void assertPromotedOnce(final Station station) {
		await().until(() -> station.node().built().size() == 2);
		assertThat(station.node().built(), contains(false, true));
	}

	private static void assertNothingElseIsBuilt(final Station station) {
		await().during(Duration.ofSeconds(1)).until(() -> station.node().built().size() == 2);
	}

	@Property(tries = 2, shrinking = ShrinkingMode.OFF)
	public void syncsTheChainOfAPeerInOrderAndPromotesTheNodeOnce() {
		final Station a = start(0, null, OPTIONS, CHAIN);
		final Station b = start(1, LedgerTestChain.seed(2),
			trusting(new Checkpoint(CHECKPOINT_HEIGHT, CHAIN.get(CHECKPOINT_HEIGHT - 1).hash())), List.of());

		connect(b, a);
		await().until(() -> b.node().height() == BLOCKS);
		assertHoldsTheChain(b);
		assertPromotedOnce(b);
		assertNothingElseIsBuilt(b);
		assertThat(b.node().driver().halted(), is(false));
		assertThat(ids(b), contains(a.listenId()));
		assertThat(ids(a), contains(b.listenId()));

		await().until(() -> sentTo(b, Heartbeat.class).contains(a.listenId()));
		assertThat(ids(a), contains(b.listenId()));
	}

	@Property(tries = 2, shrinking = ShrinkingMode.OFF)
	public void passesOnATransactionOnceAndNotBackToThePeerItCameFrom() {
		final Station a = start(0, null, OPTIONS, CHAIN);
		final Station b = start(1, LedgerTestChain.seed(2), OPTIONS, List.of());
		final Transaction transaction = LedgerTestChain.mint(BLOCKS + 1);

		connect(b, a);
		await().until(() -> b.node().height() == BLOCKS && b.node().built().size() == 2);

		assertThat(submit(a, transaction), equalTo(Optional.empty()));
		await().until(() -> b.node().ledger().pending() == 1);

		assertThat(a.node().ledger().pending(), is(1));
		assertThat(sentTo(a, Transaction.class), contains(b.listenId()));
		await().during(Duration.ofSeconds(1)).until(() -> sentTo(b, Transaction.class).isEmpty());
		assertThat(sentTo(a, Transaction.class), hasSize(1));
	}

	@Property(tries = 2, shrinking = ShrinkingMode.OFF)
	public void syncsANodeFromAPeerThatSyncedItselfWhenTheFirstIsCut() {
		final Station a = start(0, null, OPTIONS, CHAIN);
		final Station b = start(1, LedgerTestChain.seed(2), OPTIONS, List.of());
		final Dial toA = connect(b, a);

		await().until(() -> b.node().height() == BLOCKS);
		dials.remove(toA);
		hangUp(toA);
		stations.remove(a);
		stop(a);
		await().until(() -> ids(b).isEmpty());

		final Station c = start(2, LedgerTestChain.seed(3), OPTIONS, List.of());

		connect(c, b);
		await().until(() -> c.node().height() == BLOCKS);
		assertHoldsTheChain(c);
		assertPromotedOnce(c);
		assertThat(ids(c), contains(b.listenId()));
		assertThat(ids(b), contains(c.listenId()));
		assertHoldsTheChain(b);
	}

	@Property(tries = 2, shrinking = ShrinkingMode.OFF)
	public void haltsAtTheHeightOfATrustedCheckpointThatTheChainDoesNotMatch() {
		final Station a = start(0, null, OPTIONS, CHAIN);
		final byte[] wrong = CHAIN.get(CHECKPOINT_HEIGHT).hash();
		final Station d = start(1, LedgerTestChain.seed(2), trusting(new Checkpoint(CHECKPOINT_HEIGHT, wrong)),
			List.of());

		connect(d, a);
		await().until(() -> d.node().driver().halted());

		assertThat(d.node().driver().haltReason().orElseThrow(),
			containsString("Block " + CHECKPOINT_HEIGHT + " differs from the trusted checkpoint"));
		assertThat(d.node().height(), is((long) CHECKPOINT_HEIGHT - 1));
		assertThat(hexes(hashesOf(d.node())), equalTo(hexes(CHAIN.subList(0, CHECKPOINT_HEIGHT - 1).stream()
			.map(Block::hash).toList())));
		assertThat(d.node().built(), contains(false));
		assertThat(a.node().driver().halted(), is(false));
	}

	@Property(tries = 2, shrinking = ShrinkingMode.OFF)
	public void namesEachNodeOfALocalNetworkByTheListenPortAndPassesOnATransactionOnce() {
		final List<Station> nodes = List.of(start(0, null, OPTIONS, List.of()), start(1, null, OPTIONS, List.of()),
			start(2, null, OPTIONS, List.of()));

		for (final Station from : nodes) {
			nodes.stream().filter(to -> to != from).forEach(to -> connect(from, to));
		}

		for (final Station station : nodes) {
			final List<String> others = nodes.stream().filter(other -> other != station).map(Station::listenId)
				.sorted().toList();

			await().until(() -> ids(station).equals(others));
		}

		assertThat(submit(nodes.get(0), LedgerTestChain.mint(1)), equalTo(Optional.empty()));
		await().until(() -> nodes.stream().allMatch(station -> station.node().ledger().pending() == 1));

		/* The two others pass it on when it is new to them, and to everyone but the one they had it from: that is one
		   node each, whichever of the other two reached them first. What comes round again is not new. */
		await().during(Duration.ofSeconds(1)).until(() -> sentTo(nodes.get(1), Transaction.class).size() == 1
			&& sentTo(nodes.get(2), Transaction.class).size() == 1);

		assertThat(sentTo(nodes.get(0), Transaction.class), containsInAnyOrder(nodes.get(1).listenId(),
			nodes.get(2).listenId()));
	}

	@SneakyThrows
	private static Optional<String> submit(final Station station, final Transaction transaction) {
		return station.node().driver().submit(transaction).get(10, TimeUnit.SECONDS);
	}

	private static List<String> sentTo(final Station station, final Class<?> type) {
		return station.network().sent(type).stream().map(RecordingNetwork.Sent::to).toList();
	}
}
