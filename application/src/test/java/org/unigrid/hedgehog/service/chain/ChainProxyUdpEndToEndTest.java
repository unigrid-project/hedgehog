/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.jqwik.api.Example;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.service.chain.ChainProxyEndToEndTest.Network;

/* A SOCKS5 client reaches a UDP server through the whole proxy: the SOCKS front end and its datagram socket, the
   tunnel of a client node, the chains of a group of gridnodes, and an exit whose datagram socket sends to the server.
   The nodes talk over the fake streams of a fleet; only the sockets of the client, the proxy, the exit and the server
   are real. A datagram that must not be relayed is followed by one that must, from the same client: the proxy keeps
   the order of an association's datagrams at every step, so once the echo of the second is back, the first would have
   reached the server and come back before it. */
public class ChainProxyUdpEndToEndTest {
	private static final int WAIT_SECONDS = 10;
	private static final int POLL_MILLIS = 2;
	private static final int LARGE_DATAGRAM = 8000;
	private static final int ANY_PORT = 0;
	private static final int WHOLE = 0;
	private static final int SUCCEEDED = 0;
	private static final int NOT_ALLOWED = 2;

	private static byte[] bytes(final String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static Network network(final ChainFleet fleet) throws InterruptedException {
		return new Network(fleet, new Random(1), fleet.activeIds());
	}

	/* Bounded, and over as soon as the condition holds */
	private static ConditionFactory waiting() {
		return Awaitility.await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).pollDelay(Duration.ZERO)
			.pollInterval(Duration.ofMillis(POLL_MILLIS));
	}

	/* The proxy answers with a datagram socket of its own on the address the client reached it at */
	private static void associated(final SocksUdpClient client, final int port) throws IOException {
		assertThat(client.associate(port), is(SUCCEEDED));
		assertThat(client.relay().getAddress(), equalTo(LoopbackUdpEcho.loopback()));
		assertThat(client.relay().getPort(), not(ANY_PORT));
	}

	/* The next datagram the client gets is the echo of the bytes, with the echo's address in its header */
	private static void assertEchoed(final SocksUdpClient client, final LoopbackUdpEcho echo, final byte[] sent)
		throws IOException {

		final SocksUdpClient.Reply reply = client.receive();

		assertThat(reply.source(), equalTo(echo.address()));
		assertThat(reply.data(), equalTo(sent));
	}

	/* A socket is closed once its port is free again */
	private static void awaitClosed(final String whose, final InetSocketAddress socket) {
		waiting().alias(whose + " socket closes").until(() -> LoopbackUdpEcho.isFree(socket));
	}

	private static void send(final DatagramSocket socket, final InetSocketAddress to, final byte[] bytes)
		throws IOException {

		socket.send(new DatagramPacket(bytes, bytes.length, to));
	}

	/* What the proxy sent the socket before its last reply to the client has arrived by now, as one socket sends in
	   order and loopback hands a datagram over as it is sent */
	private static boolean receivedAny(final DatagramSocket socket) throws IOException {
		socket.setSoTimeout(1);

		try {
			socket.receive(new DatagramPacket(new byte[1], 1));
			return true;
		} catch (SocketTimeoutException none) {
			return false;
		}
	}

	private static InetSocketAddress unspecified(final int port) throws IOException {
		return new InetSocketAddress(InetAddress.getByAddress(new byte[4]), port);
	}

	private static DatagramSocket stranger() throws IOException {
		return new DatagramSocket(0, LoopbackUdpEcho.loopback());
	}

	@Example
	public void carriesDatagramsBothWaysBetweenASocksClientAndAUdpServer() throws Exception {
		final ChainFleet fleet = ChainFleet.group();
		final byte[] large = new byte[LARGE_DATAGRAM];

		new Random(2).nextBytes(large);

		try (LoopbackUdpEcho echo = new LoopbackUdpEcho(); Network network = network(fleet);
			SocksUdpClient client = new SocksUdpClient(network.socksPort())) {

			associated(client, ANY_PORT);
			client.send(echo.address(), bytes("hello"), WHOLE);
			assertEchoed(client, echo, bytes("hello"));
			client.send(echo.address(), large, WHOLE);
			assertEchoed(client, echo, large);
			assertThat(echo.received.get(), is(2));
			assertThat("The one socket of the exit for the association", echo.peers(), hasSize(1));
			assertThat(network.openRelays(), is(1));
		}
	}

	@Example
	public void endsTheAssociationAtTheExitWhenTheControlConnectionCloses() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		try (LoopbackUdpEcho echo = new LoopbackUdpEcho(); Network network = network(fleet);
			SocksUdpClient client = new SocksUdpClient(network.socksPort())) {

			associated(client, ANY_PORT);
			client.send(echo.address(), bytes("hello"), WHOLE);
			assertEchoed(client, echo, bytes("hello"));

			final InetSocketAddress exit = echo.peers().get(0);

			assertThat(network.openRelays(), is(1));
			assertThat("The exit's socket is held", LoopbackUdpEcho.isFree(exit), is(false));
			assertThat("The proxy's socket is held", LoopbackUdpEcho.isFree(client.relay()), is(false));

			client.closeControl();
			waiting().until(network::openRelays, is(0));
			awaitClosed("The exit's", exit);
			awaitClosed("The proxy's", client.relay());
		}
	}

	@Example
	public void relaysNoFragment() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		try (LoopbackUdpEcho echo = new LoopbackUdpEcho(); Network network = network(fleet);
			SocksUdpClient client = new SocksUdpClient(network.socksPort())) {

			associated(client, ANY_PORT);
			client.send(echo.address(), bytes("fragment"), 1);
			client.send(echo.address(), bytes("whole"), WHOLE);
			assertEchoed(client, echo, bytes("whole"));
			assertThat(echo.received.get(), is(1));
		}
	}

	/* The client named no port, so the first datagram it sends tells the proxy where it is */
	@Example
	public void relaysNothingFromAnotherSocketOnceTheClientIsKnown() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		try (LoopbackUdpEcho echo = new LoopbackUdpEcho(); Network network = network(fleet);
			SocksUdpClient client = new SocksUdpClient(network.socksPort()); DatagramSocket stranger = stranger()) {

			associated(client, ANY_PORT);
			client.send(echo.address(), bytes("first"), WHOLE);
			assertEchoed(client, echo, bytes("first"));

			send(stranger, client.relay(), SocksUdpClient.packet(echo.address(), bytes("stranger"), WHOLE));
			client.send(echo.address(), bytes("second"), WHOLE);
			assertEchoed(client, echo, bytes("second"));
			assertThat(echo.received.get(), is(2));
			assertThat(receivedAny(stranger), is(false));
		}
	}

	/* The stranger is on the client's address and sends first, which only the port the client named tells apart */
	@Example
	public void relaysNothingFromAnotherPortThanTheOneTheClientNamed() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		try (LoopbackUdpEcho echo = new LoopbackUdpEcho(); Network network = network(fleet);
			SocksUdpClient client = new SocksUdpClient(network.socksPort()); DatagramSocket stranger = stranger()) {

			associated(client, client.port());
			send(stranger, client.relay(), SocksUdpClient.packet(echo.address(), bytes("stranger"), WHOLE));
			client.send(echo.address(), bytes("named"), WHOLE);
			assertEchoed(client, echo, bytes("named"));
			assertThat(echo.received.get(), is(1));
			assertThat(receivedAny(stranger), is(false));
		}
	}

	/* 0.0.0.0 is refused by the policy. A socket that sent to it would reach the echo all the same, as Linux hands a
	   datagram for it to the local host, so a policy that let it through shows up at the echo. */
	@Example
	public void dropsDatagramsToADestinationThePolicyRefusesOrThatNothingAnswers() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		try (LoopbackUdpEcho echo = new LoopbackUdpEcho(); Network network = network(fleet);
			SocksUdpClient client = new SocksUdpClient(network.socksPort())) {

			final InetSocketAddress refused = unspecified(echo.address().getPort());
			final InetSocketAddress silent = new InetSocketAddress(LoopbackUdpEcho.loopback(),
				LoopbackUdpEcho.closedPort());

			associated(client, ANY_PORT);
			client.send(refused, bytes("refused"), WHOLE);
			client.send(silent, bytes("silent"), WHOLE);
			client.send(echo.address(), bytes("answered"), WHOLE);
			assertEchoed(client, echo, bytes("answered"));
			assertThat(echo.received.get(), is(1));
		}
	}

	/* A datagram is carried in one entry, and the chains of the fleet take entries of 16 KiB at most */
	@Example
	public void dropsADatagramThatDoesNotFitOneEntry() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		try (LoopbackUdpEcho echo = new LoopbackUdpEcho(); Network network = network(fleet);
			SocksUdpClient client = new SocksUdpClient(network.socksPort())) {

			associated(client, ANY_PORT);
			client.send(echo.address(), new byte[(int) fleet.settings.get().orElseThrow().maxEntryBytes()], WHOLE);
			client.send(echo.address(), bytes("fits"), WHOLE);
			assertEchoed(client, echo, bytes("fits"));
			assertThat(echo.received.get(), is(1));
		}
	}

	/* The stranger is on the echo's address, on another port, and sends to the exit's socket before the echo
	   answers the second datagram */
	@Example
	public void relaysNoDatagramFromASourceTheAssociationDidNotSendTo() throws Exception {
		final ChainFleet fleet = ChainFleet.group();

		try (LoopbackUdpEcho echo = new LoopbackUdpEcho(); Network network = network(fleet);
			SocksUdpClient client = new SocksUdpClient(network.socksPort()); DatagramSocket stranger = stranger()) {

			associated(client, ANY_PORT);
			client.send(echo.address(), bytes("first"), WHOLE);
			assertEchoed(client, echo, bytes("first"));

			send(stranger, echo.peers().get(0), bytes("stranger"));
			client.send(echo.address(), bytes("second"), WHOLE);
			assertEchoed(client, echo, bytes("second"));
		}
	}

	/* The refusal starts no session at all; the same network relays once the spork allows UDP */
	@Example
	public void refusesTheAssociationWhileTheSporkLacksTheUdpFlag() throws Exception {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 30,
			ProxySettings.CONNECT));

		try (LoopbackUdpEcho echo = new LoopbackUdpEcho(); Network network = network(fleet);
			SocksUdpClient refused = new SocksUdpClient(network.socksPort())) {

			assertThat(refused.associate(ANY_PORT), is(NOT_ALLOWED));
			assertThat(refused.relay(), equalTo(unspecified(0)));
			assertThat(refused.controlClosed(), is(true));
			assertThat(fleet.deliveries, is(empty()));

			fleet.settings.set(Optional.of(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 30,
				ProxySettings.CONNECT | ProxySettings.UDP)));
			fleet.refreshAll();

			try (SocksUdpClient allowed = new SocksUdpClient(network.socksPort())) {
				associated(allowed, ANY_PORT);
				allowed.send(echo.address(), bytes("allowed"), WHOLE);
				assertEchoed(allowed, echo, bytes("allowed"));
			}
		}
	}

	@Example
	public void closesAnAssociationThatHasBeenIdleForTooLongAtTheExit() throws Exception {
		idles(network -> network.exits.forEach(ChainExit::tick));
	}

	@Example
	public void closesAnAssociationThatHasBeenIdleForTooLongAtTheClient() throws Exception {
		idles(network -> network.sessions.tick());
	}

	/* The spork closes sessions after a second without traffic. One end ticks a millisecond before the limit and the
	   association still relays, which is traffic; it ticks again a second after that traffic and the association ends
	   at both ends: the proxy closes the control connection and lets go of its socket, and the exit of its own. */
	private static void idles(final Consumer<Network> tick) throws Exception {
		final ChainFleet fleet = ChainFleet.group(new ProxySettings(ChainFleet.MEMBERS, 3600, 4, 16,
			32L * 1024 * 1024, 30, 16 * 1024, 1, ProxySettings.CONNECT | ProxySettings.UDP));
		final long idleMillis = fleet.settings.get().orElseThrow().sessionIdleMillis();

		try (LoopbackUdpEcho echo = new LoopbackUdpEcho(); Network network = network(fleet);
			SocksUdpClient client = new SocksUdpClient(network.socksPort())) {

			associated(client, ANY_PORT);
			client.send(echo.address(), bytes("ping"), WHOLE);
			assertEchoed(client, echo, bytes("ping"));

			final InetSocketAddress exit = echo.peers().get(0);

			fleet.clock.addAndGet(idleMillis - 1);
			tick.accept(network);
			client.send(echo.address(), bytes("still"), WHOLE);
			assertEchoed(client, echo, bytes("still"));

			fleet.clock.addAndGet(idleMillis);
			tick.accept(network);
			assertThat(client.controlClosed(), is(true));
			waiting().until(network::openRelays, is(0));
			awaitClosed("The exit's", exit);
			awaitClosed("The proxy's", client.relay());
		}
	}
}
