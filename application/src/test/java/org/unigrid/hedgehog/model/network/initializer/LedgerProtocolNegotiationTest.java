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

package org.unigrid.hedgehog.model.network.initializer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.incubator.codec.quic.QuicChannel;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.SneakyThrows;
import mockit.Delegate;
import mockit.Expectations;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import org.jboss.weld.environment.se.WeldContainer;
import org.objenesis.ObjenesisStd;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.Instances;
import org.unigrid.hedgehog.jqwik.NamedCDIProvider;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.BlockRequest;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Status;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.handler.LedgerStatusChannelHandler;
import org.unigrid.hedgehog.model.network.handler.PingChannelHandler;
import org.unigrid.hedgehog.model.network.packet.LedgerStatus;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.service.ledger.LedgerGateway;
import org.unigrid.hedgehog.service.ledger.LedgerProtocol;
import org.unigrid.hedgehog.service.ledger.LedgerWireFixtures;

/* The ledger protocol token of the handshake is what lets a node tell a node that speaks the ledger from one that does
   not. Three nodes of one process: a node of this release (NEW), a node that offers what an earlier release offered
   (OLD), and a place for the nodes that dial, which have their own topology and gateway (DIALLER). OLD is made while
   Network.getProtocols is what the earlier release offered, and a node installs the ledger pipeline only if it offers
   the ledger protocol, so it also has no ledger stage in its pipeline: a ledger packet sent to it would break its
   stream, which is what the tests of it look for. */
@WeldSetup(TestServer.class)
public class LedgerProtocolNegotiationTest extends BaseMockedWeldTest {
	private static final String[] THIS_RELEASE = Network.getProtocols();
	private static final AtomicReference<String[]> OFFERED = new AtomicReference<>(THIS_RELEASE);
	private static final String[] EARLIER_RELEASE = { "hedgehog/0.0.4", "gridspork/0.0.4" };
	private static final String EARLIER_PROTOCOL = "hedgehog/0.0.4";
	private static final int NEW = 0;
	private static final int OLD = 1;
	private static final int DIALLER = 2;
	private static final int UNUSED_PORT = 1;
	private static final long PROBE = 4_242_424_242L;
	private static final Duration QUIET = Duration.ofMillis(1500);

	private record Call(String method, LedgerPeer from, Object argument) {
	}

	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject @Instances(3) private List<TestServer> servers;

	private final Map<LedgerDriver, List<Call>> calls = new ConcurrentHashMap<>();
	private final Map<String, Integer> ports = new HashMap<>();
	private final List<P2PClient> clients = new CopyOnWriteArrayList<>();
	private LedgerDriver newDriver;
	private LedgerDriver diallerDriver;

	private String container(final int index) {
		return getClass().getSimpleName() + (index + 1);
	}

	private <T> T within(final int index, final Supplier<T> action) {
		final AtomicReference<T> result = new AtomicReference<>();

		NamedCDIProvider.within(container(index), () -> result.set(action.get()));
		return result.get();
	}

	private <T> T bean(final int index, final Class<T> type) {
		return WeldContainer.instance(container(index)).select(type).get();
	}

	/* A mock-up of the class stays for the rest of the run and cannot be taken down, so one is made for all of it,
	   and what it answers is what the node being made offers */
	@BeforeContainer
	private static void letTheTestSayWhatIsOffered() {
		new MockUp<Network>() {
			@Mock public String[] getProtocols() {
				return OFFERED.get();
			}
		};
	}

	private static <T> T whileOffering(final String[] protocols, final Supplier<T> action) {
		OFFERED.set(protocols);

		try {
			return action.get();
		} finally {
			OFFERED.set(THIS_RELEASE);
		}
	}

	/* The two servers are made one after the other, the second while the protocols are those of the earlier release,
	   and each is given the port of its own for what it says in its hello and what a topology takes to be itself */
	@BeforeTry
	private void startTheNodes() {
		within(NEW, () -> {
			TestServer.mockProperties(servers.get(NEW));
			return null;
		});

		whileOffering(EARLIER_RELEASE, () -> within(OLD, () -> {
			TestServer.mockProperties(servers.get(OLD));
			return null;
		}));

		ports.put(container(NEW), servers.get(NEW).getP2p().getPort());
		ports.put(container(OLD), servers.get(OLD).getP2p().getPort());
		ports.put(container(DIALLER), UNUSED_PORT);

		new Expectations() {{
			NetOptions.getPort(); minTimes = 0;
			result = new Delegate<Integer>() {
				int port() {
					return ports.get(((WeldContainer) CDI.current()).getId());
				}
			};
		}};

		calls.clear();
		recordTheCallsOfTheDrivers();
		newDriver = attachADriverTo(NEW);
		diallerDriver = attachADriverTo(DIALLER);
	}

	@AfterTry
	private void shutDown() {
		clients.forEach(P2PClient::closeDirty);
		clients.clear();
		bean(NEW, LedgerGateway.class).detach();
		bean(DIALLER, LedgerGateway.class).detach();

		within(NEW, () -> {
			bean(NEW, Topology.class).clear();
			bean(DIALLER, Topology.class).clear();
			return null;
		});
	}

	private void record(final Invocation invocation, final String method, final LedgerPeer from,
		final Object argument) {

		calls.computeIfAbsent((LedgerDriver) invocation.getInvokedInstance(), driver -> new CopyOnWriteArrayList<>())
			.add(new Call(method, from, argument));
	}

	private void recordTheCallsOfTheDrivers() {
		new MockUp<LedgerDriver>() {
			@Mock public void onTransaction(Invocation invocation, LedgerPeer from, Transaction transaction,
				int bytes) {

				record(invocation, "onTransaction", from, transaction);
			}

			@Mock public void onMessage(Invocation invocation, LedgerPeer from, Message message, int bytes) {
				record(invocation, "onMessage", from, message);
			}

			@Mock public void onHeartbeat(Invocation invocation, LedgerPeer from, Heartbeat heartbeat, int bytes) {
				record(invocation, "onHeartbeat", from, heartbeat);
			}

			@Mock public void onStatus(Invocation invocation, LedgerPeer from, Status status, int bytes) {
				record(invocation, "onStatus", from, status);
			}

			@Mock public CompletableFuture<BlockBatch> onBlockRequest(Invocation invocation, LedgerPeer from,
				BlockRequest request, int bytes) {

				record(invocation, "onBlockRequest", from, request);
				return CompletableFuture.completedFuture(LedgerWireFixtures.emptyBatch());
			}

			@Mock public void onPeerConnected(Invocation invocation, LedgerPeer from) {
				record(invocation, "onPeerConnected", from, null);
			}
		};
	}

	private LedgerDriver attachADriverTo(final int index) {
		final LedgerDriver driver = new ObjenesisStd().newInstance(LedgerDriver.class);

		bean(index, LedgerGateway.class).attach(driver);
		return driver;
	}

	private List<String> methodsHeardBy(final LedgerDriver driver) {
		return calls.getOrDefault(driver, List.of()).stream().map(Call::method).toList();
	}

	/* What the peers told the driver, not that a stream was announced to it */
	private List<String> heardBy(final LedgerDriver driver) {
		return methodsHeardBy(driver).stream().filter(method -> !"onPeerConnected".equals(method)).toList();
	}

	private List<Call> callsOf(final LedgerDriver driver) {
		return calls.getOrDefault(driver, List.of());
	}

	@SneakyThrows
	private P2PClient newNodeDials(final int index) {
		return within(DIALLER, () -> dial(index));
	}

	/* A node of the earlier release dials: what it offers is what the earlier release offered, and so is its pipeline */
	@SneakyThrows
	private P2PClient earlierNodeDials(final int index) {
		return whileOffering(EARLIER_RELEASE, () -> within(DIALLER, () -> dial(index)));
	}

	/* The way a node dials another: the topology of the dialling node holds the node with the stream it opened */
	@SneakyThrows
	private P2PClient dial(final int index) {
		final TestServer server = servers.get(index);
		final P2PClient client = new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());
		final Node node = Node.builder().address(new InetSocketAddress(server.getP2p().getHostName(),
			server.getP2p().getPort())).connection(Optional.of(client)).build();
		final Topology topology = bean(DIALLER, Topology.class);

		clients.add(client);
		topology.addNode(node);
		return client;
	}

	/* What the handshake of the connection of a stream settled on */
	private static String agreedOn(final Channel stream) {
		return ((QuicChannel) stream.parent()).sslEngine().getApplicationProtocol();
	}

	/* The streams that the nodes which dialled opened to a node, as its topology holds them after their hello */
	private List<Channel> streamsAcceptedBy(final int index) {
		return within(index, () -> bean(index, Topology.class).cloneNodes().stream()
			.flatMap(node -> node.getConnection().stream()).map(connection -> connection.getChannel())
			.filter(Channel::isActive).toList());
	}

	private List<Channel> streamsAcceptedByNew() {
		return streamsAcceptedBy(NEW);
	}

	private static boolean isOpen(final P2PClient client) {
		return client.getChannel().isActive() && !client.getChannel().isInputShutdown();
	}

	private static boolean isAnswerToTheProbe(final Ping ping) {
		return ping.isResponse() && ping.getNanoTime() == PROBE;
	}

	/* The node is answered when its probe, a ping of a time that no other ping has, comes back marked as an answer,
	   which only a node that understands it does; the pings that every node sends of itself are left out */
	private static List<Ping> answersToTheProbeOf(final P2PClient client) {
		final List<Ping> answers = new CopyOnWriteArrayList<>();
		final ChannelPipeline pipeline = client.getChannel().pipeline();

		pipeline.addBefore(pipeline.context(PingChannelHandler.class).name(), null,
			new ChannelInboundHandlerAdapter() {
				@Override
				public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
					if (message instanceof Ping ping && isAnswerToTheProbe(ping)) {
						answers.add(ping);
					}

					ctx.fireChannelRead(message);
				}
			});

		return answers;
	}

	private void assertAnswersAPing(final P2PClient client) {
		final List<Ping> answers = answersToTheProbeOf(client);

		client.send(Ping.builder().nanoTime(PROBE).build());
		await().until(() -> answers.size() == 1);
	}

	private LedgerStatus status(final int seed) {
		return LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build();
	}

	@Example
	public void settlesOnTheLedgerProtocolBetweenTwoNewNodesAndTheLedgerFlowsBothWays() {
		final LedgerGateway dialler = bean(DIALLER, LedgerGateway.class);
		final LedgerGateway accepting = bean(NEW, LedgerGateway.class);
		final P2PClient client = newNodeDials(NEW);

		assertThat(agreedOn(client.getChannel()), equalTo(Network.LEDGER_PROTOCOL));
		assertThat(LedgerProtocol.negotiatedOn(client.getChannel()), is(true));

		await().until(() -> streamsAcceptedByNew().size() == 1);

		final Channel accepted = streamsAcceptedByNew().get(0);

		assertThat(agreedOn(accepted), equalTo(Network.LEDGER_PROTOCOL));
		assertThat(LedgerProtocol.negotiatedOn(accepted), is(true));
		assertThat(accepted.pipeline().get(LedgerStatusChannelHandler.class), notNullValue());
		assertThat(dialler.network().peers(), hasSize(1));

		client.send(status(1));
		await().until(() -> methodsHeardBy(newDriver).contains("onStatus"));
		assertThat(callsOf(newDriver).stream().filter(call -> "onStatus".equals(call.method())).findFirst().get()
			.argument(), equalTo(LedgerWireFixtures.status(1)));

		await().until(() -> accepting.network().peers().size() == 1);
		accepting.network().peers().get(0).send(LedgerWireFixtures.status(2));
		await().until(() -> methodsHeardBy(diallerDriver).contains("onStatus"));

		await().until(() -> methodsHeardBy(newDriver).contains("onPeerConnected"));
		await().until(() -> methodsHeardBy(diallerDriver).contains("onPeerConnected"));
		assertAnswersAPing(client);
	}

	@Example
	public void fallsBackToTheEarlierProtocolWhenANewNodeDialsANodeOfTheEarlierRelease() {
		final LedgerGateway dialler = bean(DIALLER, LedgerGateway.class);
		final P2PClient client = newNodeDials(OLD);

		assertThat(agreedOn(client.getChannel()), equalTo(EARLIER_PROTOCOL));
		assertThat(LedgerProtocol.negotiatedOn(client.getChannel()), is(false));

		await().until(() -> streamsAcceptedBy(OLD).size() == 1);
		assertThat(streamsAcceptedBy(OLD).get(0).pipeline().get(LedgerStatusChannelHandler.class), nullValue());

		await().during(QUIET).until(() -> dialler.network().peers().isEmpty()
			&& methodsHeardBy(diallerDriver).isEmpty());

		dialler.peerOf(client.getChannel(), () -> Optional.empty()).send(LedgerWireFixtures.status(1));
		assertAnswersAPing(client);
		assertThat(isOpen(client), is(true));
	}

	/* What the gate keeps from happening: a ledger frame sent to a node of the earlier release breaks its stream, as
	   no stage there takes the frame and what comes after it is read from the wrong place */
	@Example
	public void breaksTheStreamOfANodeOfTheEarlierReleaseThatIsSentALedgerFrame() {
		final P2PClient client = newNodeDials(OLD);
		final List<Ping> answers = answersToTheProbeOf(client);

		client.getChannel().writeAndFlush(LedgerWireFixtures.encoded(status(1)));
		client.send(Ping.builder().nanoTime(PROBE).build());

		await().until(() -> !isOpen(client));
		assertThat(answers, hasSize(0));
	}

	@Example
	public void servesANodeOfTheEarlierReleaseThatDialsANewNodeAndSeesNoPeerOfTheLedgerInIt() {
		final LedgerGateway accepting = bean(NEW, LedgerGateway.class);
		final P2PClient client = earlierNodeDials(NEW);

		assertThat(agreedOn(client.getChannel()), equalTo(EARLIER_PROTOCOL));
		assertAnswersAPing(client);
		await().until(() -> streamsAcceptedByNew().size() == 1);

		final Channel accepted = streamsAcceptedByNew().get(0);

		assertThat(agreedOn(accepted), equalTo(EARLIER_PROTOCOL));
		assertThat(LedgerProtocol.negotiatedOn(accepted), is(false));
		await().during(QUIET).until(() -> accepting.network().peers().isEmpty()
			&& methodsHeardBy(newDriver).isEmpty());
		assertThat(isOpen(client), is(true));
	}

	@Example
	public void closesTheStreamOfANodeOfTheEarlierReleaseThatSendsTheLedgerAllTheSameAndServesOthers() {
		final P2PClient earlier = earlierNodeDials(NEW);
		final P2PClient other = newNodeDials(NEW);

		earlier.getChannel().writeAndFlush(LedgerWireFixtures.encoded(status(1)));
		await().until(() -> !isOpen(earlier));
		assertThat(heardBy(newDriver), is(List.of()));

		other.send(status(2));
		await().until(() -> methodsHeardBy(newDriver).contains("onStatus"));
		assertThat(callsOf(newDriver).stream().filter(call -> "onStatus".equals(call.method())).map(Call::argument)
			.toList(), contains(LedgerWireFixtures.status(2)));
		assertThat(isOpen(other), is(true));
	}

	@Example
	public void takesTheProtocolThatTheServerListsFirst() {
		final String[] listingTheLedgerLast = { EARLIER_PROTOCOL, "gridspork/0.0.4", Network.LEDGER_PROTOCOL };
		final P2PClient client = whileOffering(listingTheLedgerLast, () -> within(DIALLER, () -> dial(NEW)));

		assertThat(agreedOn(client.getChannel()), equalTo(Network.LEDGER_PROTOCOL));
	}
}
