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

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicConnectionEvent;
import jakarta.inject.Inject;
import java.lang.reflect.Constructor;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.transport.handler.ConnectionHandler;

/* The slot that a connection takes of its address with the limiter, from the moment the server learns where it comes
   from until it closes, however far its handshake got: a peer that gives up halfway must not hold a slot for good, or
   an address that did so a few times over would be shut out of the node until it restarts. */
@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class ConnectionSlotTest extends BaseMockedWeldTest {
	private static final InetAddress PEER = InetAddress.ofLiteral("192.0.2.7");
	private static final int PORT = 4000;
	private static final int WAIT_SECONDS = 5;

	/* Only starts the container that CDI.current() reaches the topology in */
	@Inject private Topology topology;

	/* The connection of a handshake that never ended is not active, so it never goes inactive when it closes: it
	   is closed and unregistered, as QuicHalfOpen shows with the real thing */
	private static final class Unfinished extends EmbeddedChannel {
		private Unfinished(ChannelHandler handler) {
			super(handler);
		}

		@Override
		public boolean isActive() {
			return false;
		}
	}

	/* The event of the codec that tells where a connection comes from, which only the codec can make */
	@SneakyThrows
	private static QuicConnectionEvent comingFrom(InetAddress address) {
		final Constructor<QuicConnectionEvent> constructor = QuicConnectionEvent.class
			.getDeclaredConstructor(SocketAddress.class, SocketAddress.class);

		constructor.setAccessible(true);
		return constructor.newInstance(null, new InetSocketAddress(address, PORT));
	}

	@SneakyThrows
	private static Optional<InetAddress> addressOfThisHost() {
		return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
			.filter(ConnectionSlotTest::isUp).flatMap(nic -> Collections.list(nic.getInetAddresses()).stream())
			.filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress()
				&& !address.isLinkLocalAddress()).findFirst();
	}

	@SneakyThrows
	private static boolean isUp(NetworkInterface nic) {
		return nic.isUp();
	}

	@Example
	public void givesBackTheSlotOfAConnectionThatWasActive() {
		final ConnectionLimiter limiter = new ConnectionLimiter();
		final EmbeddedChannel connection = new EmbeddedChannel(new ConnectionHandler(limiter));

		connection.pipeline().fireUserEventTriggered(comingFrom(PEER));
		assertThat(limiter.held(PEER), equalTo(1));

		connection.close();
		assertThat(limiter.held(PEER), equalTo(0));
	}

	@Example
	public void givesBackTheSlotOfAConnectionThatClosedBeforeItsHandshakeWasDone() {
		final ConnectionLimiter limiter = new ConnectionLimiter();
		final EmbeddedChannel connection = new Unfinished(new ConnectionHandler(limiter));

		connection.pipeline().fireUserEventTriggered(comingFrom(PEER));
		assertThat(limiter.held(PEER), equalTo(1));

		connection.close();
		assertThat(limiter.held(PEER), equalTo(0));
	}

	/* Over the real thing, from an address of this host that is not exempt as the loopback address is */
	@Example
	public void givesBackTheSlotOfAHandshakeThatTheClientAbandonedHalfway() throws InterruptedException {
		final Optional<InetAddress> host = addressOfThisHost();
		Assume.that(host.isPresent());

		final ConnectionLimiter limiter = new ConnectionLimiter();

		try (QuicHalfOpen halfOpen = new QuicHalfOpen(host.get(), new ConnectionHandler(limiter))) {
			final QuicChannel connection = halfOpen.serverEnd();

			assertThat(connection.isActive(), is(false));
			assertThat(limiter.held(host.get()), equalTo(1));

			connection.close().sync();
			await().atMost(WAIT_SECONDS, TimeUnit.SECONDS)
				.untilAsserted(() -> assertThat(limiter.held(host.get()), equalTo(0)));
		}
	}
}
