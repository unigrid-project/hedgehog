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

import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.proxy.exit.ExitContext;
import org.unigrid.hedgehog.proxy.exit.NettyUdpConnector;
import org.unigrid.hedgehog.proxy.exit.Target;
import org.unigrid.hedgehog.proxy.exit.TargetListener;
import org.unigrid.hedgehog.proxy.exit.UdpConnector;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;

/* What a gridnode really connects with, as ChainExit's own tests swap it for a fake */
public class ExitContextsTest {
	private static final String CONNECTOR_THREADS = "chain-exit-connector";
	private static final int WAIT_SECONDS = 15;

	private static List<Thread> connectorThreads() {
		return Thread.getAllStackTraces().keySet().stream()
			.filter(thread -> thread.getName().startsWith(CONNECTOR_THREADS)).toList();
	}

	/* What a datagram connector answers */
	private static final class Opening implements UdpConnector.Listener {
		final CompletableFuture<UdpConnector.Socket> opened = new CompletableFuture<>();
		final CompletableFuture<Socks5CommandStatus> failed = new CompletableFuture<>();
		final CompletableFuture<Boolean> closed = new CompletableFuture<>();

		@Override
		public void opened(final UdpConnector.Socket socket) {
			opened.complete(socket);
		}

		@Override
		public void failed(final Socks5CommandStatus status) {
			failed.complete(status);
		}

		@Override
		public boolean accepts(final InetSocketAddress source) {
			return false;
		}

		@Override
		public void received(final InetSocketAddress source, final byte[] bytes) {
			/* Nothing is accepted */
		}

		@Override
		public void closed() {
			closed.complete(true);
		}
	}

	/* The client sizes its deadline on this value and on eight addresses */
	@Example
	public void connectsWithTheTimeoutTheClientsDeadlineIsSizedOn() {
		assertThat(ExitContexts.CONNECT_TIMEOUT_MILLIS, is(10_000));
	}

	@Example
	public void refusesDatagramsWhenNoDatagramConnectorIsGiven() {
		final Opening opening = new Opening();
		final ExitContexts contexts = new ExitContexts(DestinationPolicy.publicOnly(), new FakeConnector(),
			Runnable::run, () -> { });

		contexts.of(ProxySettings.DEFAULTS).udp().open(opening);

		assertThat(opening.failed.getNow(null), is(Socks5CommandStatus.COMMAND_UNSUPPORTED));
		assertThat(opening.opened.isDone(), is(false));
	}

	@Example
	public void handsEveryContextItsParts() {
		final FakeConnector connector = new FakeConnector();
		final Executor resolving = Runnable::run;
		final UdpConnector udp = listener -> { };
		final AtomicInteger released = new AtomicInteger();
		final ExitContexts contexts = new ExitContexts(DestinationPolicy.allowLoopback(), connector, resolving,
			released::incrementAndGet, udp);
		final ExitContext context = contexts.of(ProxySettings.DEFAULTS);

		assertThat(context.settings(), is(ProxySettings.DEFAULTS));
		assertThat(context.policy(), is(sameInstance(DestinationPolicy.allowLoopback())));
		assertThat(context.connector(), is(sameInstance(connector)));
		assertThat(context.resolving(), is(sameInstance(resolving)));
		assertThat(context.udp(), is(sameInstance(udp)));
		contexts.close();
		assertThat(released.get(), is(1));
	}

	/* A wall clock would be some 1.7 trillion milliseconds, the monotonic one counts from an arbitrary origin */
	@Example
	public void agesDestinationsOnTheMonotonicClock() {
		final ExitContexts contexts = new ExitContexts(DestinationPolicy.publicOnly(), new FakeConnector(),
			Runnable::run, () -> { });
		final long before = System.nanoTime() / 1_000_000;
		final long now = contexts.of(ProxySettings.DEFAULTS).clock().getAsLong();
		final long after = System.nanoTime() / 1_000_000;

		assertThat(now, is(both(greaterThanOrEqualTo(before)).and(lessThanOrEqualTo(after))));
	}

	@Example
	public void relaysDatagramsOverNettyInProduction() {
		final ExitContexts contexts = ExitContexts.production();

		try {
			final ExitContext context = contexts.of(ProxySettings.DEFAULTS);

			assertThat(context.udp(), instanceOf(NettyUdpConnector.class));
			assertThat(context.policy(), is(sameInstance(DestinationPolicy.publicOnly())));
			assertThat(connectorThreads(), hasSize(0));
		} finally {
			contexts.close();
		}
	}

	@Example
	public void releasesTwiceWithoutHarm() {
		final ExitContexts contexts = ExitContexts.production();

		contexts.of(ProxySettings.DEFAULTS);
		contexts.close();
		contexts.close();

		assertThat(connectorThreads(), hasSize(0));
	}

	@Example
	public void connectsOnItsOwnDaemonThreadsAndStopsThemWhenReleased() throws Exception {
		final ExitContexts production = ExitContexts.production();
		final CompletableFuture<Target> connected = new CompletableFuture<>();

		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			assertThat(connectorThreads(), hasSize(0));
			production.of(ProxySettings.DEFAULTS).connector().connect(List.of(InetAddress.getLoopbackAddress()),
				server.getLocalPort(), new TargetListener() {
					@Override
					public void connected(final Target target) {
						connected.complete(target);
					}

					@Override
					public void failed(final Socks5CommandStatus status) {
						connected.completeExceptionally(new IllegalStateException(status.toString()));
					}

					@Override
					public void received(final byte[] bytes) {
						/* Nothing is sent to it */
					}

					@Override
					public void closed() {
						/* Not of interest */
					}
				});
			connected.get(WAIT_SECONDS, TimeUnit.SECONDS).close();
			assertThat(connectorThreads(), is(not(empty())));
			assertThat(connectorThreads().stream().map(Thread::isDaemon).toList(),
				everyItem(is(true)));
		} finally {
			production.close();
		}

		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> connectorThreads().isEmpty());
	}

	/* The datagram sockets share the connector's loops, which start with the first socket and close it on release */
	@Example
	public void opensDatagramSocketsOnItsOwnDaemonThreadsAndStopsThemWhenReleased() throws Exception {
		final ExitContexts production = ExitContexts.production();
		final Opening opening = new Opening();

		try {
			assertThat(connectorThreads(), hasSize(0));
			production.of(ProxySettings.DEFAULTS).udp().open(opening);
			assertThat(opening.opened.get(WAIT_SECONDS, TimeUnit.SECONDS).localAddress().getAddress()
				.isAnyLocalAddress(), is(true));
			assertThat(connectorThreads(), is(not(empty())));
			assertThat(connectorThreads().stream().map(Thread::isDaemon).toList(), everyItem(is(true)));
		} finally {
			production.close();
		}

		assertThat(opening.closed.get(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> connectorThreads().isEmpty());
	}

	@Example
	public void failsDatagramSocketsOnceReleasedWithoutStartingAThread() {
		final ExitContexts production = ExitContexts.production();
		final Opening opening = new Opening();

		production.close();
		production.of(ProxySettings.DEFAULTS).udp().open(opening);

		assertThat(opening.failed.getNow(null), is(Socks5CommandStatus.FAILURE));
		assertThat(connectorThreads(), hasSize(0));
	}
}
