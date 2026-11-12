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

package org.unigrid.hedgehog.model.network;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.inject.Inject;
import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainerHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.ContainerLifecycleContext;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.ConnectionLimiter.Admission;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.option.OptionsCli;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.model.option.S3Options;
import org.unigrid.hedgehog.server.BaseDefaultNodeTest;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.transport.handler.ConnectionHandler;

/* A node that was told how many connections to admit in all holds that ceiling where its P2P server lets peers in.
   Every peer of a test comes from this host, and the node leaves the loopback address alone, so the node listens on an
   address of a private network of this host where there is one: a peer that dials it there comes from that address,
   which is not exempt. The others that fill the node are addresses the limiter is asked for directly, as no test can
   connect from them. */
@AddLifecycleHook(InboundCeilingDefaultNodeTest.LowCeiling.class)
public class InboundCeilingDefaultNodeTest extends BaseDefaultNodeTest {
	/* The least ceiling that a node takes */
	private static final int CEILING = ConnectionLimiter.MAX_PER_ADDRESS;
	private static final Duration WAIT = Duration.ofSeconds(30);

	private static final String CEILING_REACHED = "The ceiling of " + CEILING + " inbound connections is reached, new "
		+ "peers are refused; a seed may need more: option --netmaxinbound";

	private static final Optional<InetAddress> HOST = privateAddressOfThisHost();

	/* Where the peers of this test come from */
	private static final InetAddress PEER = HOST.orElse(InetAddress.getLoopbackAddress());

	@Inject private P2PServer p2p;

	private final Logger logger = (Logger) LoggerFactory.getLogger(ConnectionHandler.class);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	/* Parses what the hook of the base has, on the ports it found free, and the ceiling besides, with the address of
	   this host to listen on where it has one. The higher proximity makes it run after that hook, and the container
	   starts with the first example, after both. */
	public static class LowCeiling implements BeforeContainerHook {
		@Override
		public void beforeContainer(ContainerLifecycleContext context) {
			OptionsCli.parse(List.of("daemon"), "--nethost",
				HOST.map(InetAddress::getHostAddress).orElse(NetOptions.getHost()), "--netport",
				String.valueOf(NetOptions.getPort()), "--resthost", RestOptions.getHost(), "--restport",
				String.valueOf(RestOptions.getPort()), "--resttoken", RestOptions.getToken(), "--no-seeds",
				"--s3host", S3Options.getHost(), "--s3port", String.valueOf(S3Options.getPort()),
				"--netmaxinbound", String.valueOf(CEILING));
		}

		@Override
		public int beforeContainerProximity() {
			return 10;
		}
	}

	@SneakyThrows
	private static Optional<InetAddress> privateAddressOfThisHost() {
		return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
			.filter(InboundCeilingDefaultNodeTest::isUp)
			.flatMap(nic -> Collections.list(nic.getInetAddresses()).stream())
			.filter(address -> address instanceof Inet4Address && address.isSiteLocalAddress()).findFirst();
	}

	@SneakyThrows
	private static boolean isUp(NetworkInterface nic) {
		return nic.isUp();
	}

	@BeforeTry
	public void listen() {
		appender.list.clear();
		appender.start();
		logger.addAppender(appender);
	}

	@AfterTry
	public void stopListening() {
		logger.detachAppender(appender);
		appender.stop();
	}

	private List<String> warnings() {
		synchronized (appender) {
			return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
				.map(ILoggingEvent::getFormattedMessage).toList();
		}
	}

	/* The limiter of the server, which the handler of every connection asks */
	private ConnectionLimiter limiter() throws ReflectiveOperationException {
		final Field field = P2PServer.class.getDeclaredField("limiter");

		field.setAccessible(true);
		return (ConnectionLimiter) field.get(CDIUtil.unproxy(p2p));
	}

	/* Hosts of the network kept for documentation, which nothing connects from: one more than the node admits */
	private static List<InetAddress> others() {
		return IntStream.rangeClosed(1, CEILING + 1).mapToObj(n -> InetAddress.ofLiteral("192.0.2." + n)).toList();
	}

	private static QuicPeer dial() {
		return QuicPeer.dial(NetOptions.getHost(), NetOptions.getPort(), Network.getProtocols());
	}

	/* Whether the node serves a peer that dials it now. One that it refuses fails its handshake, or is closed right
	   after it and so before the node answers its ping. */
	private static boolean isServed() {
		try (QuicPeer peer = dial()) {
			final QuicPeer.Stream main = peer.open();
			final long ping = main.ping();

			main.awaitAnswerOrClose(ping);
			return main.hasAnswerToPing(ping);
		} catch (Exception refused) {
			return false;
		}
	}

	/* Without this the example below would hold for a node that was never given the option */
	@Example
	public void shouldAdmitAsManyConnectionsInAllAsTheOptionSays() throws ReflectiveOperationException {
		final ConnectionLimiter limiter = limiter();
		final List<InetAddress> others = others();

		assertThat(NetOptions.getMaxInbound(), equalTo(CEILING));
		assertThat(limiter.getCeiling(), equalTo(CEILING));

		try {
			others.subList(0, CEILING)
				.forEach(other -> assertThat(limiter.decide(other), is(Admission.ADMITTED)));
			assertThat(limiter.decide(others.get(CEILING)), is(Admission.NODE_FULL));
		} finally {
			others.forEach(limiter::release);
		}
	}

	/* Over the real thing. The others hold all that the node admits, so a peer from the address of this host, which
	   holds nothing, is refused for the ceiling: the node tells its operator of it, and holds nothing of the peer. Once
	   one of the others is gone the same peer is served, which shows that it reaches the node at all. On a host with
	   no such address the peer is of the loopback address, which the node serves whatever it holds. */
	@Example
	public void shouldRefuseAPeerThatIsNotOfTheLoopbackAddressWhileOthersHoldAllItAdmits() throws Exception {
		final ConnectionLimiter limiter = limiter();
		final List<InetAddress> others = others();

		try {
			others.subList(0, CEILING).forEach(other -> assertThat(limiter.admit(other), is(true)));

			if (HOST.isPresent()) {
				assertThat(isServed(), is(false));
				await().atMost(WAIT).untilAsserted(() -> assertThat(warnings(), contains(CEILING_REACHED)));
				assertThat(limiter.held(HOST.get()), equalTo(0));
			} else {
				assertThat(isServed(), is(true));
				assertThat(warnings(), empty());
			}

			limiter.release(others.get(0));

			try (QuicPeer peer = dial()) {
				final QuicPeer.Stream main = peer.open();

				main.awaitAnswer(main.ping());
				assertThat(limiter.held(PEER), equalTo(HOST.isPresent() ? 1 : 0));
			}

			await().atMost(WAIT).untilAsserted(() -> assertThat(limiter.held(PEER), equalTo(0)));
			assertThat(warnings(), hasSize(HOST.isPresent() ? 1 : 0));
		} finally {
			others.forEach(limiter::release);
		}
	}
}
