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

package org.unigrid.hedgehog.proxy.exit;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterProperty;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.Close;
import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.Connected;
import org.unigrid.hedgehog.proxy.session.Data;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/* A client session, its exit relay and a real TCP destination on 127.0.0.1. The name of the destination resolves
   to 127.0.0.2, where nothing listens, and 127.0.0.1; only the permissive test policy admits either. */
public class ExitRelayLoopbackTest {
	private static final String NAME = "echo.test";
	private static final byte[] PAYLOAD = { 1, 2, 3 };

	/* What the client may send to a destination that takes nothing before the test gives up on the session being
	   dropped: the backlog bound plus whatever socket buffers the host grants a loopback connection */
	private static final long MAX_STALLED_BYTES = 64L * 1024 * 1024;

	/* Connect timeouts and waits: a patient one outlasts the second after which an unanswered SYN is sent again, an
	   impatient one gives up on an address well within it */
	private static final int PATIENT_MILLIS = 2000;
	private static final int IMPATIENT_MILLIS = 300;

	private final NioEventLoopGroup group = new NioEventLoopGroup(1);
	private final List<SealedMessage> atClient = new ArrayList<>();

	@AfterProperty
	public void stopEventLoop() {
		group.shutdownGracefully(0, 1, TimeUnit.SECONDS)
			.awaitUninterruptibly(RecordingTargetListener.TIMEOUT_MILLIS);
	}

	/* Chunks stay below what one entry carries, which the client's send refuses to exceed */
	@Provide
	public Arbitrary<List<byte[]>> chunks() {
		return Arbitraries.bytes().array(byte[].class).ofMinSize(1).ofMaxSize(16_000).list().ofMinSize(1)
			.ofMaxSize(8);
	}

	private static SessionPair pairFor(int port) {
		return new SessionPair(new Connect(Socks5CommandType.CONNECT, new SocksAddress(Socks5AddressType.DOMAIN,
			NAME, port)));
	}

	private ExitRelay open(SessionPair pair) throws InterruptedException {
		final ExitRelay relay = new ExitRelay(pair.exit, new ExitContext(ProxySettings.DEFAULTS,
			DestinationPolicy.allowLoopback(), host -> List.of(EchoServer.loopback(2), EchoServer.LOOPBACK),
			Runnable::run, new NettyExitConnector(group, 2000)));

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
	public void shouldRoundTripAStreamThroughTheExit(@ForAll("chunks") List<byte[]> chunks) throws Exception {
		atClient.clear();

		try (EchoServer echo = new EchoServer()) {
			final SessionPair pair = pairFor(echo.port());
			final ExitRelay relay = open(pair);
			final ByteArrayOutputStream sent = new ByteArrayOutputStream();
			final ByteArrayOutputStream echoed = new ByteArrayOutputStream();

			assertThat(((Connected) atClient.remove(0)).status(), is(Socks5CommandStatus.SUCCESS));
			chunks.forEach(chunk -> {
				pair.client.send(new Data(chunk));
				sent.writeBytes(chunk);
			});
			pair.toExit().forEach(relay::handle);

			RecordingTargetListener.await(() -> {
				atClient.addAll(pair.toClient());
				atClient.forEach(message -> echoed.writeBytes(((Data) message).bytes()));
				atClient.clear();
				return echoed.size() >= sent.size();
			});

			assertThat(echoed.toByteArray(), equalTo(sent.toByteArray()));
			pair.client.close(CloseReason.DONE);
			pair.toExit().forEach(relay::handle);
			assertThat(relay.isOpen(), is(false));
			RecordingTargetListener.await(() -> echo.open() == 0);
			assertThat(echo.echoed(), is((long) sent.size()));
		}
	}

	@Example
	public void shouldTellTheClientWhenTheDestinationHangsUp() throws Exception {
		final SessionPair pair;
		final ExitRelay relay;

		try (EchoServer echo = new EchoServer()) {
			pair = pairFor(echo.port());
			relay = open(pair);

			final SocksAddress bound = ((Connected) atClient.get(0)).bound();

			assertThat(bound.host(), is("127.0.0.1"));
			assertThat(bound.port(), is(echo.peerPort()));
		}

		awaitAtClient(pair, 2);
		assertThat(atClient.get(1), equalTo(new Close(CloseReason.DONE)));
		assertThat(relay.isOpen(), is(false));
	}

	@Example
	public void shouldHoldBackTheDestinationWhileTheChainIsFullAndResumeAfterwards() throws Exception {
		try (EchoServer echo = new EchoServer()) {
			final SessionPair pair = pairFor(echo.port());
			final ExitRelay relay = open(pair);

			relay.setChainWritable(false);
			pair.client.send(new Data(PAYLOAD));
			pair.toExit().forEach(relay::handle);
			RecordingTargetListener.await(() -> echo.echoed() >= PAYLOAD.length);

			/* A task of its own on the event loop runs after any read that was already pending */
			group.next().submit(() -> { }).get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			assertThat(pair.toClient(), is(empty()));
			relay.setChainWritable(true);

			awaitAtClient(pair, 2);
			assertThat(atClient.get(1), equalTo(new Data(PAYLOAD)));
		}
	}

	@Example
	public void shouldCutALargeDownloadIntoEntrySizedPieces() throws Exception {
		final byte[] download = new byte[1024 * 1024];

		new Random(7).nextBytes(download);

		try (ServerSocket server = new ServerSocket(0, 1, EchoServer.LOOPBACK)) {
			final Thread sender = new Thread(() -> upload(server, download));

			sender.setDaemon(true);
			sender.start();

			final SessionPair pair = pairFor(server.getLocalPort());

			open(pair);
			RecordingTargetListener.await(() -> {
				atClient.addAll(pair.toClient());
				return atClient.get(atClient.size() - 1) instanceof Close;
			});

			final ByteArrayOutputStream received = new ByteArrayOutputStream();

			for (SealedMessage message : atClient.subList(1, atClient.size() - 1)) {
				final byte[] piece = ((Data) message).bytes();

				assertThat(piece.length <= pair.exit.maxDataBytes(), is(true));
				received.writeBytes(piece);
			}

			assertThat(Arrays.equals(received.toByteArray(), download), is(true));
			assertThat(atClient.get(atClient.size() - 1), equalTo(new Close(CloseReason.DONE)));
		}
	}

	/* The destination of a download: accepts one connection, writes everything and hangs up */
	private static void upload(ServerSocket server, byte[] bytes) {
		try (Socket socket = server.accept()) {
			socket.getOutputStream().write(bytes);
		} catch (IOException closed) {
			/* The test went away; what it missed shows in its assertions */
		}
	}

	/* A relay whose destination's name resolves to the given addresses, still connecting when it returns */
	private ExitRelay connecting(SessionPair pair, List<InetAddress> addresses, int connectTimeoutMillis) {
		final ExitRelay relay = new ExitRelay(pair.exit, new ExitContext(ProxySettings.DEFAULTS,
			DestinationPolicy.allowLoopback(), host -> addresses, Runnable::run,
			new NettyExitConnector(group, connectTimeoutMillis)));

		pair.toExit().forEach(relay::handle);
		return relay;
	}

	/* Returns once the event loop ran what the end of the relay asked of it */
	private void leave(SessionPair pair, ExitRelay relay) throws Exception {
		pair.client.close(CloseReason.DONE);
		pair.toExit().forEach(relay::handle);
		assertThat(relay.isOpen(), is(false));
		group.next().submit(() -> { }).get(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
	}

	@Example
	public void shouldCloseTheConnectionBeingDialledWhenTheClientLeaves() throws Exception {
		try (Blackhole hole = new Blackhole()) {
			final SessionPair pair = pairFor(hole.port());

			leave(pair, connecting(pair, List.of(EchoServer.LOOPBACK), PATIENT_MILLIS));
			assertThat(hole.takesAnotherWithin(PATIENT_MILLIS), is(false));
		}
	}

	@Example
	public void shouldDialNoFurtherAddressWhenTheClientLeaves() throws Exception {
		try (ServerSocket next = new ServerSocket(0, 1, EchoServer.LOOPBACK);
			Blackhole hole = new Blackhole(EchoServer.loopback(3), next.getLocalPort())) {
			final SessionPair pair = pairFor(hole.port());

			leave(pair, connecting(pair, List.of(EchoServer.loopback(3), EchoServer.LOOPBACK), IMPATIENT_MILLIS));
			next.setSoTimeout(PATIENT_MILLIS);
			Expect.assertThrows(SocketTimeoutException.class, next::accept);
		}
	}

	@Example
	public void shouldDropTheSessionWhenTheDestinationFallsTooFarBehind() throws Exception {
		try (ServerSocket stalled = new ServerSocket()) {
			/* Never accepted: the kernel completes the connection and fills the receive buffer, the smallest it
			   grants, and then takes nothing more */
			stalled.setReceiveBufferSize(1);
			stalled.bind(new InetSocketAddress(EchoServer.LOOPBACK, 0), 1);

			final SessionPair pair = pairFor(stalled.getLocalPort());
			final ExitRelay relay = open(pair);
			final byte[] chunk = new byte[pair.exit.maxDataBytes()];

			for (long sent = 0; relay.isOpen() && sent < MAX_STALLED_BYTES; sent += chunk.length) {
				pair.client.send(new Data(chunk));
				pair.toExit().forEach(relay::handle);
			}

			assertThat(relay.isOpen(), is(false));
			awaitAtClient(pair, 2);
			assertThat(atClient.get(1), equalTo(new Close(CloseReason.BROKEN)));
		}
	}
}
