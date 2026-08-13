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

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.proxy.exit.ExitConnector;
import org.unigrid.hedgehog.proxy.exit.ExitContext;
import org.unigrid.hedgehog.proxy.exit.NettyExitConnector;
import org.unigrid.hedgehog.proxy.exit.TargetListener;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.Resolver;

/* What the exit relays of a node share besides the spork: the destination policy, the connector and the executor
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

	/* The container starts the exit at every daemon start, also with the proxy off, so no thread of either starts
	   before a relay needs one */
	static ExitContexts production() {
		final ThreadPoolExecutor resolving = resolver();
		final LazyConnector connector = new LazyConnector();

		return new ExitContexts(DestinationPolicy.publicOnly(), connector, resolving, () -> {
			resolving.shutdownNow();
			connector.release();
		});
	}

	/* A lookup blocks for as long as the name server takes, and the client chooses the names, so a stranger that asks
	   for names that never answer fills these threads and the queue behind them, which holds one lookup for every
	   relay, and is then refused: it does not fill the node with threads */
	private static ThreadPoolExecutor resolver() {
		final ThreadPoolExecutor resolving = new ThreadPoolExecutor(RESOLVER_THREADS, RESOLVER_THREADS,
			RESOLVER_IDLE_SECONDS, TimeUnit.SECONDS, new ArrayBlockingQueue<>(ChainExit.MAX_RELAYS),
			new DefaultThreadFactory("chain-exit-resolver", true));

		resolving.allowCoreThreadTimeOut(true);
		return resolving;
	}

	ExitContext of(final ProxySettings settings) {
		return new ExitContext(settings, policy, Resolver.system(), resolving, connector);
	}

	void close() {
		release.run();
	}

	/* The event loops start with the first connection a relay asks for, and none start once the exit stopped */
	private static final class LazyConnector implements ExitConnector {
		private static final ExitConnector RELEASED = (addresses, port, listener) ->
			listener.failed(Socks5CommandStatus.FAILURE);

		private EventLoopGroup group;
		private ExitConnector connector;

		@Override
		public void connect(final List<InetAddress> addresses, final int port, final TargetListener listener) {
			connector().connect(addresses, port, listener);
		}

		private synchronized ExitConnector connector() {
			if (connector == null) {
				group = new NioEventLoopGroup(CONNECTOR_THREADS,
					new DefaultThreadFactory("chain-exit-connector", true));
				connector = new NettyExitConnector(group, CONNECT_TIMEOUT_MILLIS);
			}

			return connector;
		}

		/* A connector on a group that is shut down fails what it is asked for, as the released one does */
		private synchronized void release() {
			if (group == null) {
				connector = RELEASED;
			} else {
				group.shutdownGracefully();
			}
		}
	}
}
