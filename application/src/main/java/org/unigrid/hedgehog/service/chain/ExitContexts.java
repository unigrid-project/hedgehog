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

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.geo.HostLiteral;
import org.unigrid.hedgehog.proxy.exit.ExitConnector;
import org.unigrid.hedgehog.proxy.exit.ExitContext;
import org.unigrid.hedgehog.proxy.exit.NettyExitConnector;
import org.unigrid.hedgehog.proxy.exit.NettyUdpConnector;
import org.unigrid.hedgehog.proxy.exit.TargetListener;
import org.unigrid.hedgehog.proxy.exit.UdpConnector;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.Resolver;

/* What the exit relays of a node share besides the spork: the destination policy, the connectors and the executor
   names are looked up on. The policy is the only gate between a stranger's request and a connection, so the one
   way to get these in production is `production()`, which never allows what `publicOnly` refuses. Tests build
   their own with the package-private constructor. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ExitContexts {
	static final int RESOLVER_THREADS = 32;
	private static final int RESOLVER_IDLE_SECONDS = 30;
	private static final int CONNECTOR_THREADS = 2;
	static final int CONNECT_TIMEOUT_MILLIS = 10_000;

	private final DestinationPolicy policy;
	private final ExitConnector connector;
	private final Executor resolving;
	private final Runnable release;
	private final UdpConnector udp;

	/* A node that relays streams and no datagrams */
	ExitContexts(final DestinationPolicy policy, final ExitConnector connector, final Executor resolving,
		final Runnable release) {

		this(policy, connector, resolving, release, UdpConnector.unsupported());
	}

	/* The container starts the exit at every daemon start, also with the proxy off, so no thread of either starts
	   before a relay needs one */
	static ExitContexts production() {
		final ThreadPoolExecutor resolving = resolver();
		final LazyConnector connector = new LazyConnector();

		return new ExitContexts(DestinationPolicy.publicOnly(), connector, resolving, () -> {
			resolving.shutdownNow();
			connector.release();
		}, new NettyUdpConnector(connector::group));
	}

	/* The announced address when its host is an IP literal of one of this machine's interfaces, which is what the
	   exit can bind to. Behind 1:1 NAT the announced address is no interface's own, and the country that clients
	   choose by is read from a literal only, so a name is never resolved. */
	static Optional<InetAddress> localAddressOf(final String announced) {
		return HostLiteral.of(announced).filter(ExitContexts::isOwn);
	}

	private static boolean isOwn(final InetAddress address) {
		try {
			return NetworkInterface.getByInetAddress(address) != null;
		} catch (SocketException ex) {
			return false;
		}
	}

	/* Only names are looked up here, never an IP literal: one for each stream relay and up to four at once for each
	   association. A lookup blocks for as long as the name server takes, and the client chooses the names, so a
	   stranger that asks for names that never answer fills these threads and the queue behind them and is then
	   refused: it does not fill the node with threads. */
	private static ThreadPoolExecutor resolver() {
		final ThreadPoolExecutor resolving = new ThreadPoolExecutor(RESOLVER_THREADS, RESOLVER_THREADS,
			RESOLVER_IDLE_SECONDS, TimeUnit.SECONDS, new ArrayBlockingQueue<>(ChainExit.MAX_RELAYS),
			new DefaultThreadFactory("chain-exit-resolver", true));

		resolving.allowCoreThreadTimeOut(true);
		return resolving;
	}

	ExitContext of(final ProxySettings settings) {
		return new ExitContext(settings, policy, Resolver.system(), resolving, connector, udp,
			ExitContext.MONOTONIC_MILLIS);
	}

	void close() {
		release.run();
	}

	/* The event loops start with the first connection or datagram a relay asks for, and none start once the exit
	   stopped */
	private static final class LazyConnector implements ExitConnector {
		private static final ExitConnector RELEASED = (addresses, port, listener) -> {
			listener.failed(Socks5CommandStatus.FAILURE);
			return Attempt.NONE;
		};

		private EventLoopGroup group;
		private ExitConnector connector;
		private boolean released;

		@Override
		public Attempt connect(final List<InetAddress> addresses, final int port, final TargetListener listener) {
			return connector().connect(addresses, port, listener);
		}

		/* The datagram connector shares these loops; once released there are none, and it fails what it is asked */
		synchronized EventLoopGroup group() {
			if (released) {
				return null;
			}

			if (group == null) {
				group = new NioEventLoopGroup(CONNECTOR_THREADS,
					new DefaultThreadFactory("chain-exit-connector", true));
			}

			return group;
		}

		private synchronized ExitConnector connector() {
			if (released) {
				return RELEASED;
			}

			if (connector == null) {
				connector = new NettyExitConnector(group(), CONNECT_TIMEOUT_MILLIS);
			}

			return connector;
		}

		/* After the release every connect and every datagram open fails at once, whether or not a group was built */
		private synchronized void release() {
			released = true;

			if (group != null) {
				group.shutdownGracefully();
			}
		}
	}
}
