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

package org.unigrid.hedgehog.service.chain;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.model.geo.CountryLookup;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Tag;
import org.unigrid.hedgehog.proxy.exit.NettyExitConnector;
import org.unigrid.hedgehog.proxy.exit.NettyUdpConnector;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.ClientSession;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import org.unigrid.hedgehog.proxy.socks.SocksInitializer;

/* A SOCKS5 client reaches a TCP server through the whole proxy: the SOCKS front end, the tunnel of a client node,
   the chains of a group of gridnodes, and an exit that connects to the server. The nodes talk over the fake
   streams of a fleet, which a thread of the test delivers; only the SOCKS connections and the server are real. Time
   is the fleet's clock, which the test moves, and the nodes tick when the test ticks them. */
public class ChainProxyEndToEndTest {
	private static final String CLIENT = "client";
	private static final String LOOPBACK = "127.0.0.1";
	private static final int PUMP_MILLIS = 2;
	private static final int CONNECT_TIMEOUT_MILLIS = 5000;
	private static final int WAIT_SECONDS = 10;
	private static final int SEVERAL_ENTRIES_OF_BYTES = 100_000;
	private static final long EPOCH_MILLIS = 3_600_000;
	private static final long RETENTION_MILLIS = 30_000;
	private static final byte[] NOTHING = new byte[0];

	/* A client node, exits on the members it is told, a SOCKS listener in front of the client and a pump that hands
	   over what the nodes wrote to each other every two milliseconds, a hop at a time, as the event loops of real
	   streams would. Shared with the UDP test. */
	static final class Network implements AutoCloseable {
		private final ChainFleet fleet;
		private final EventLoopGroup clients = group("socks-client");
		private final EventLoopGroup proxy = group("socks-proxy");
		private final EventLoopGroup destinations = group("exit-connector");
		private final ScheduledThreadPoolExecutor pump = new ScheduledThreadPoolExecutor(1, daemons("fleet-pump"));
		private final ExecutorService joins = Executors.newSingleThreadExecutor(daemons("client-join"));
		final List<ChainExit> exits = new ArrayList<>();
		private final ExitContexts contexts;
		final ClientSessions sessions;
		private final Channel listener;
		private final ScheduledFuture<?> pumping;

		Network(final ChainFleet fleet, final Random random, final List<String> onDuty)
			throws InterruptedException {

			this.fleet = fleet;
			contexts = new ExitContexts(DestinationPolicy.allowLoopback(),
				new NettyExitConnector(destinations, CONNECT_TIMEOUT_MILLIS), Runnable::run, () -> { },
				new NettyUdpConnector(destinations));
			sessions = new ClientSessions(new ChainClient(fleet.settings::get, fleet.directory(CLIENT),
				CountryLookup.NONE, fleet.transport(CLIENT), fleet.host(CLIENT), fleet.pending(CLIENT),
				fleet.clock::get, random, joins), fleet.host(CLIENT), fleet.settings::get, fleet.clock::get, new SecureRandom(),
				new ManualScheduler());
			onDuty.forEach(this::exit);
			pump.setRemoveOnCancelPolicy(true);
			pump.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
			listener = new ServerBootstrap().group(proxy).channel(NioServerSocketChannel.class)
				.childHandler(new SocksInitializer(new ChainTunnel(fleet.settings::get, sessions, pump)))
				.bind(LOOPBACK, 0).sync().channel();
			pumping = pump.scheduleWithFixedDelay(fleet::hop, 0, PUMP_MILLIS, TimeUnit.MILLISECONDS);
		}

		private static EventLoopGroup group(final String name) {
			return new NioEventLoopGroup(1, daemons(name));
		}

		private static DefaultThreadFactory daemons(final String name) {
			return new DefaultThreadFactory(name, true);
		}

		private void exit(final String id) {
			final ChainExit exit = new ChainExit(fleet.host(id), fleet.settings::get, ChainFleet.sealingKey(id),
				fleet.clock::get, new SecureRandom(), contexts, new ManualScheduler());

			exit.start();
			exits.add(exit);
		}

		int socksPort() {
			return ((InetSocketAddress) listener.localAddress()).getPort();
		}

		private SocksClient connect(final int port, final byte[] early) throws InterruptedException {
			return new SocksClient(clients, socksPort(),
				new SocksAddress(Socks5AddressType.IPv4, LOOPBACK, port), early);
		}

		int openRelays() {
			return exits.stream().mapToInt(ChainExit::openRelays).sum();
		}

		/* The deadlines of the tunnel's streams that are still to run, as a cancelled one leaves the queue */
		private long deadlines() {
			return pump.getQueue().stream().filter(task -> task != pumping).count();
		}

		/* The entries on the chains of the group, as its first member holds them */
		private List<Entry> entries() {
			final ChainHost member = fleet.host(fleet.activeIds().get(0));

			return member.hostedChains().stream().flatMap(chain -> member.chain(chain).orElseThrow().entries()
				.stream()).toList();
		}

		/* The openings among them: the entries that the sealing key of a gridnode opens */
		private List<Entry> openings() {
			return entries().stream().filter(entry -> ChainFleet.openerOf(entry, ids()).isPresent()).toList();
		}

		/* The answers among them: the entries under the answer tag of an opening */
		private List<Entry> answers() {
			final Set<Tag> tags = openings().stream().map(opening -> ChainFleet.answerTagOf(opening,
				named(opening))).collect(Collectors.toSet());

			return entries().stream().filter(entry -> tags.contains(entry.header().tag())).toList();
		}

		/* The gridnode an opening is sealed to */
		private String named(final Entry opening) {
			return ChainFleet.openerOf(opening, ids()).orElseThrow();
		}

		private List<String> ids() {
			return fleet.gridnodes.stream().map(Gridnode::getId).toList();
		}

		/* The shutdown cancels the pump's task, so a task that is done and not cancelled once the pump has stopped
		   ended with a round that threw, and get() throws what it threw */
		@Override
		public void close() throws Exception {
			pump.shutdown();
			joins.shutdownNow();
			listener.close();
			exits.forEach(ChainExit::stop);
			groups().forEach(group -> group.shutdownGracefully(0, WAIT_SECONDS, TimeUnit.SECONDS));

			final boolean stopped = stopped();

			if (pumping.isDone() && !pumping.isCancelled()) {
				pumping.get();
			}

			assertThat("No thread of the network is stuck", stopped, is(true));
		}

		private boolean stopped() throws InterruptedException {
			return pump.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
				&& joins.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) && groups().stream().allMatch(
				group -> group.terminationFuture().awaitUninterruptibly(WAIT_SECONDS, TimeUnit.SECONDS));
		}

		private List<EventLoopGroup> groups() {
			return List.of(clients, proxy, destinations);
		}
	}

	private static byte[] bytes(final String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] severalEntriesOfBytes() {
		final byte[] bytes = new byte[SEVERAL_ENTRIES_OF_BYTES];

		new Random(2).nextBytes(bytes);
		return bytes;
	}

	private static byte[] join(final byte[] first, final byte[] second) {
		final ByteArrayOutputStream joined = new ByteArrayOutputStream();

		joined.writeBytes(first);
		joined.writeBytes(second);
		return joined.toByteArray();
	}

	/* Bounded, and over as soon as the condition holds */
	private static ConditionFactory waiting() {
		return Awaitility.await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).pollDelay(Duration.ZERO)
			.pollInterval(Duration.ofMillis(PUMP_MILLIS));
	}

	/* The reply to a stream that opened names no address: where the exit connects from is for the exit alone to
	   know */
	private static void opened(final SocksClient client) throws Exception {
		final Socks5CommandResponse reply = client.reply();

		assertThat(reply.status(), is(Socks5CommandStatus.SUCCESS));
		assertThat(new SocksAddress(reply.bndAddrType(), reply.bndAddr(), reply.bndPort()),
			equalTo(SocksAddress.UNSPECIFIED));
	}

	/* What the client got back is what it sent, compared by length and at the first byte that differs, as a hundred
	   thousand bytes do not fit a message */
	private static void assertReceived(final SocksClient client, final byte[] sent) {
		waiting().until(() -> client.received().length, greaterThanOrEqualTo(sent.length));

		final byte[] received = client.received();

		assertThat(received.length, is(sent.length));
		assertThat("The first byte that differs", Arrays.mismatch(received, sent), is(-1));
	}

	/* The front end replies to a stream that never opened with no address and hangs up */
	private static void assertRefused(final SocksClient client, final Socks5CommandStatus status) throws Exception {
		final Socks5CommandResponse reply = client.reply();

		assertThat(reply.status(), is(status));
		assertThat(new SocksAddress(reply.bndAddrType(), reply.bndAddr(), reply.bndPort()),
			equalTo(SocksAddress.UNSPECIFIED));
		client.awaitClosed();
	}

	/* The client was hung up on, the server saw the exit's connection go and no exit holds the relay any more */
	private static void assertEnded(final Network network, final LoopbackEcho echo, final SocksClient client)
		throws Exception {

		client.awaitClosed();
		assertThat(echo.disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
		waiting().until(network::openRelays, is(0));
	}

	/* The client sends hello right behind its request, before the reply, as a client may */
	@Example
	public void carriesBytesBothWaysBetweenASocksClientAndATcpServer() throws Exception {
		final ChainFleet fleet = ChainFleet.group();
		final byte[] large = severalEntriesOfBytes();

		try (LoopbackEcho echo = new LoopbackEcho(); Network network = new Network(fleet, new Random(1),
			fleet.activeIds()); SocksClient client = network.connect(echo.port(), bytes("hello"))) {

			opened(client);

			assertReceived(client, bytes("hello"));
			client.write(large);
			assertReceived(client, join(bytes("hello"), large));
			assertThat(echo.peers, hasSize(1));
			assertThat(network.openRelays(), is(1));
			assertThat(network.deadlines(), is(0L));
			assertThat(network.openings(), hasSize(1));
			assertThat(network.answers(), hasSize(1));
		}
	}

	@Example
	public void triesAnotherGroupWhenTheFirstOneRefuses() throws Exception {
		final ChainFleet fleet = ChainClientTest.twoGroups(16, 4, Set.of(1));
		final List<String> refusing = fleet.view().groups().members(0);
		final List<String> onDuty = fleet.view().groups().members(1);

		try (LoopbackEcho echo = new LoopbackEcho(); Network network = new Network(fleet,
			ChainClientTest.choosing(fleet, 0), onDuty);
			SocksClient client = network.connect(echo.port(), NOTHING)) {

			opened(client);

			client.write(bytes("again"));
			assertReceived(client, bytes("again"));
			assertThat(echo.peers, hasSize(1));
			assertThat(ChainClientTest.joinsTo(fleet, refusing), is(1L));
			assertThat(ChainClientTest.joinsTo(fleet, onDuty), is(2L));
		}
	}

	@Example
	public void refusesTheConnectionWhileTheSporkIsAbsent() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		fleet.settings.set(Optional.empty());

		try (LoopbackEcho echo = new LoopbackEcho(); Network network = new Network(fleet, new Random(1),
			fleet.activeIds()); SocksClient client = network.connect(echo.port(), NOTHING)) {

			assertRefused(client, Socks5CommandStatus.FORBIDDEN);
			assertThat(network.deadlines(), is(0L));
			assertThat(echo.peers, is(empty()));
			assertThat(fleet.deliveries, is(empty()));
		}
	}

	@Example
	public void closesASessionThatHasBeenIdleForTooLongAtTheExit() throws Exception {
		idles(network -> network.exits.forEach(ChainExit::tick));
	}

	@Example
	public void closesASessionThatHasBeenIdleForTooLongAtTheClient() throws Exception {
		idles(network -> network.sessions.tick());
	}

	/* The spork closes sessions after a second without traffic. Two seconds pass and one end ticks, which closes the
	   session at both ends. */
	private static void idles(final Consumer<Network> tick) throws Exception {
		final ChainFleet fleet = ChainFleet.group(new ProxySettings(ChainFleet.MEMBERS, 3600, 4, 16,
			32L * 1024 * 1024, 30, 16 * 1024, 1, ProxySettings.CONNECT));

		try (LoopbackEcho echo = new LoopbackEcho(); Network network = new Network(fleet, new Random(1),
			fleet.activeIds()); SocksClient client = network.connect(echo.port(), NOTHING)) {

			opened(client);

			client.write(bytes("ping"));
			assertReceived(client, bytes("ping"));
			assertThat(echo.peers, hasSize(1));
			assertThat(network.openRelays(), is(1));

			fleet.clock.addAndGet(2000);
			tick.accept(network);
			assertEnded(network, echo, client);
		}
	}

	@Example
	public void endsTheConnectionToTheServerWhenTheSocksClientHangsUp() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		try (LoopbackEcho echo = new LoopbackEcho(); Network network = new Network(fleet, new Random(1),
			fleet.activeIds()); SocksClient client = network.connect(echo.port(), NOTHING)) {

			opened(client);
			client.write(bytes("bye"));
			assertReceived(client, bytes("bye"));

			client.close();
			assertEnded(network, echo, client);
		}
	}

	/* Two clients at once each get back what they sent and nothing of the other's, over two sessions of one node */
	@Example
	public void keepsTwoConnectionsApartWhenTheyRunAtOnce() throws Exception {
		final ChainFleet fleet = ChainFleet.group();
		final byte[] first = severalEntriesOfBytes();
		final byte[] second = bytes("second");

		try (LoopbackEcho echo = new LoopbackEcho(); Network network = new Network(fleet, new Random(1),
			fleet.activeIds()); SocksClient one = network.connect(echo.port(), NOTHING);
			SocksClient other = network.connect(echo.port(), NOTHING)) {

			opened(one);
			opened(other);

			one.write(first);
			other.write(second);
			assertReceived(one, first);
			assertReceived(other, second);
			assertThat(echo.peers, hasSize(2));
			assertThat(network.openRelays(), is(2));
			assertThat(network.openings(), hasSize(2));
		}
	}

	/* The server hangs up right after the last byte it sends back, and the client still gets every byte first */
	@Example
	public void hangsUpOnTheSocksClientOnceItHasWhatTheServerSentBeforeItHungUp() throws Exception {
		final ChainFleet fleet = ChainFleet.group();
		final byte[] large = severalEntriesOfBytes();

		try (LoopbackEcho echo = new LoopbackEcho(large.length); Network network = new Network(fleet, new Random(1),
			fleet.activeIds()); SocksClient client = network.connect(echo.port(), NOTHING)) {

			opened(client);
			client.write(large);
			assertEnded(network, echo, client);
			assertReceived(client, large);
		}
	}

	@Example
	public void answersConnectionRefusedForAPortThatNothingListensOn() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		try (Network network = new Network(fleet, new Random(1), fleet.activeIds());
			SocksClient client = network.connect(LoopbackEcho.closedPort(), NOTHING)) {

			assertRefused(client, Socks5CommandStatus.CONNECTION_REFUSED);
			assertThat(network.deadlines(), is(0L));
			waiting().until(network::openRelays, is(0));
		}
	}

	/* No exit runs on the member the first opening is sealed to, so it goes unanswered until the accept timeout has
	   passed and the session asks another member */
	@Example
	public void passesOverAnExitThatDoesNotAnswer() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		try (LoopbackEcho echo = new LoopbackEcho(); Network network = new Network(fleet, new Random(1), List.of());
			SocksClient client = network.connect(echo.port(), NOTHING)) {

			final List<Entry> opens = waiting().until(() -> network.openings(), hasSize(1));
			final String silent = network.named(opens.get(0));

			fleet.activeIds().stream().filter(id -> !id.equals(silent)).forEach(network::exit);
			fleet.clock.addAndGet(ClientSession.ACCEPT_TIMEOUT_MILLIS);
			network.sessions.tick();

			opened(client);

			client.write(bytes("other"));
			assertReceived(client, bytes("other"));
			assertThat(echo.peers, hasSize(1));
			assertThat(network.openings().stream().map(network::named).toList(),
				contains(equalTo(silent), not(equalTo(silent))));
			assertThat(network.answers(), hasSize(1));
		}
	}

	/* The exit stays quiet for longer than the chains keep entries while the client goes on sending. The members the
	   client is attached through keep the exit's feed, which has not ended, so the next reply of the exit reaches the
	   client. */
	@Example
	public void carriesTheNextReplyOfAnExitThatStayedQuietPastTheRetentionWhileTheClientSent() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		try (LoopbackEcho echo = new LoopbackEcho(); Network network = new Network(fleet, new Random(1),
			fleet.activeIds()); SocksClient client = network.connect(echo.port(), NOTHING)) {

			opened(client);
			client.write(bytes("one"));
			assertReceived(client, bytes("one"));
			echo.hold();

			for (final String part : List.of("two", "three")) {
				final long before = echo.received.get();

				client.write(bytes(part));
				waiting().until(echo.received::get, is(before + part.length()));
				fleet.clock.addAndGet(RETENTION_MILLIS + 1000);
				everyNodeTicks(fleet, network);
			}

			echo.release();
			assertReceived(client, bytes("onetwothree"));
		}
	}

	/* The exit's member alone ticks at the end of the grace. It closes its relay while the members the client is
	   attached through still hold the chain, so they pass the Close on to the client. */
	@Example
	public void endsASessionOnAChainOfAPastEpochOnceTheGraceIsOverAtTheExit() throws Exception {
		drains((network, exit) -> exit.tick());
	}

	@Example
	public void endsASessionOnAChainOfAPastEpochOnceTheGraceIsOverAtTheClient() throws Exception {
		drains((network, exit) -> network.sessions.tick());
	}

	/* A session on a chain of the epoch that ended runs on, and a new one starts on a chain of the new epoch. Every
	   node ticks at the last millisecond of the grace and the old session still carries bytes; a millisecond later one
	   end ticks, which ends the old session at both ends, and then no gridnode keeps a retired chain. An epoch of an
	   hour leaves the grace at DRAIN_GRACE_MILLIS. */
	private static void drains(final BiConsumer<Network, ChainHost> endOfGrace) throws Exception {
		final ChainFleet fleet = ChainFleet.group(new ProxySettings(ChainFleet.MEMBERS, 3600, 4, 16,
			32L * 1024 * 1024, 30, 16 * 1024, 10_000, ProxySettings.CONNECT));

		try (LoopbackEcho echo = new LoopbackEcho(); Network network = new Network(fleet, new Random(1),
			fleet.activeIds()); SocksClient old = network.connect(echo.port(), NOTHING)) {

			opened(old);

			old.write(bytes("one"));
			assertReceived(old, bytes("one"));

			final List<Entry> opens = network.openings();

			assertThat(opens, hasSize(1));

			final ChainHost exit = fleet.host(network.named(opens.get(0)));
			final List<ChainId> retiring = exit.hostedChains();

			fleet.clock.addAndGet(EPOCH_MILLIS);
			everyNodeTicks(fleet, network);
			assertThat(network.openings(), is(empty()));
			old.write(bytes("two"));
			assertReceived(old, bytes("onetwo"));

			try (SocksClient fresh = network.connect(echo.port(), NOTHING)) {
				opened(fresh);

				fresh.write(bytes("new"));
				assertReceived(fresh, bytes("new"));
				assertThat(network.openings(), hasSize(1));

				fleet.clock.addAndGet(ChainHost.DRAIN_GRACE_MILLIS - 1);
				everyNodeTicks(fleet, network);
				old.write(bytes("three"));
				assertReceived(old, bytes("onetwothree"));
				assertThat(network.openRelays(), is(2));
				assertThat(exit.retiredChains(), equalTo(retiring));

				fleet.clock.incrementAndGet();
				endOfGrace.accept(network, exit);
				old.awaitClosed();
				assertThat(echo.disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
				waiting().until(network::openRelays, is(1));

				fleet.activeIds().forEach(id -> fleet.host(id).tick());
				fleet.activeIds().forEach(id -> assertThat(fleet.host(id).retiredChains(), is(empty())));
				fresh.write(bytes("more"));
				assertReceived(fresh, bytes("newmore"));
				assertThat(echo.peers, hasSize(2));
			}
		}
	}

	/* What every node does once a second: the hosts, the exits on the gridnodes and the sessions of the client */
	private static void everyNodeTicks(final ChainFleet fleet, final Network network) {
		Stream.concat(fleet.activeIds().stream(), Stream.of(CLIENT)).forEach(id -> fleet.host(id).tick());
		network.exits.forEach(ChainExit::tick);
		network.sessions.tick();
	}
}
