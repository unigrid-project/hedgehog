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

package org.unigrid.hedgehog.proxy.exit;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterProperty;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.Connected;
import org.unigrid.hedgehog.proxy.session.Datagram;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/* A client session, its exit relay with the real datagram socket and a real UDP echo on 127.0.0.1. Only the
   permissive test policy admits the echo. */
public class ExitRelayUdpLoopbackTest {
	private final NioEventLoopGroup group = new NioEventLoopGroup(1);
	private final List<UdpConnector.Socket> sockets = new CopyOnWriteArrayList<>();
	private final AtomicInteger closedSockets = new AtomicInteger();
	private final List<SealedMessage> atClient = new ArrayList<>();

	@AfterProperty
	public void stopEventLoop() {
		group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
	}

	@Provide
	public Arbitrary<List<byte[]>> datagrams() {
		return Arbitraries.bytes().array(byte[].class).ofMinSize(1).ofMaxSize(1400).list().ofMinSize(1).ofMaxSize(8);
	}

	/* Hands over the socket the relay opens too, so that a test knows where the exit listens */
	private UdpConnector recording() {
		final UdpConnector real = new NettyUdpConnector(group);

		return listener -> real.open(new UdpConnector.Listener() {
			@Override
			public void opened(UdpConnector.Socket socket) {
				sockets.add(socket);
				listener.opened(socket);
			}

			@Override
			public void failed(Socks5CommandStatus status) {
				listener.failed(status);
			}

			@Override
			public boolean accepts(InetSocketAddress source) {
				return listener.accepts(source);
			}

			@Override
			public void received(InetSocketAddress source, byte[] bytes) {
				listener.received(source, bytes);
			}

			@Override
			public void closed() {
				closedSockets.incrementAndGet();
				listener.closed();
			}
		});
	}

	private ExitRelay open(SessionPair pair) throws InterruptedException {
		final ExitRelay relay = new ExitRelay(pair.exit, new ExitContext(ProxySettings.DEFAULTS,
			DestinationPolicy.allowLoopback(), host -> List.of(), Runnable::run, new FakeConnector(),
			recording(), () -> System.nanoTime() / 1_000_000));

		pair.toExit().forEach(relay::handle);
		awaitAtClient(pair, 1);
		return relay;
	}

	private void awaitAtClient(SessionPair pair, int count) throws InterruptedException {
		RecordingTargetListener.await(() -> {
			atClient.addAll(pair.toClient());
			return atClient.size() >= count;
		});
	}

	@Property(tries = 5)
	public void shouldRoundTripDatagramsThroughTheExit(@ForAll("datagrams") List<byte[]> datagrams) throws Exception {
		atClient.clear();

		try (UdpEcho echo = new UdpEcho()) {
			final SocksAddress destination = new SocksAddress(Socks5AddressType.IPv4, "127.0.0.1", echo.port());
			final SessionPair pair = new SessionPair(new Connect(Socks5CommandType.UDP_ASSOCIATE,
				SocksAddress.UNSPECIFIED));
			final ExitRelay relay = open(pair);

			assertThat(atClient.remove(0), equalTo(new Connected(Socks5CommandStatus.SUCCESS,
				SocksAddress.UNSPECIFIED)));

			for (byte[] datagram : datagrams) {
				pair.client.send(new Datagram(destination, datagram));
			}

			pair.toExit().forEach(relay::handle);
			awaitAtClient(pair, datagrams.size());

			for (byte[] datagram : datagrams) {
				assertThat(atClient.contains(new Datagram(destination, datagram)), is(true));
			}

			pair.client.close(CloseReason.DONE);
			pair.toExit().forEach(relay::handle);
			assertThat(relay.isOpen(), is(false));
			RecordingTargetListener.await(() -> closedSockets.get() == sockets.size());
		}
	}

	@Example
	public void shouldRelayNothingThatWasNotAskedFor() throws Exception {
		atClient.clear();

		try (UdpEcho echo = new UdpEcho(); DatagramSocket stranger = new DatagramSocket(0, EchoServer.LOOPBACK)) {
			final SessionPair pair = new SessionPair(new Connect(Socks5CommandType.UDP_ASSOCIATE,
				SocksAddress.UNSPECIFIED));
			final ExitRelay relay = open(pair);
			final SocksAddress destination = new SocksAddress(Socks5AddressType.IPv4, "127.0.0.1", echo.port());
			final byte[] unsolicited = { 6, 6, 6 };

			pair.client.send(new Datagram(destination, new byte[] { 1 }));
			pair.toExit().forEach(relay::handle);
			awaitAtClient(pair, 2);

			stranger.send(new DatagramPacket(unsolicited, unsolicited.length, EchoServer.LOOPBACK,
				sockets.get(0).localAddress().getPort()));
			RecordingTargetListener.await(() -> relay.droppedDatagrams() == 1);

			assertThat(pair.toClient().isEmpty(), is(true));
		}
	}
}
