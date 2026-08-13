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
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import org.unigrid.hedgehog.proxy.exit.Target;
import org.unigrid.hedgehog.proxy.exit.TargetListener;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* What a gridnode really connects with, as ChainExit's own tests swap it for a fake */
public class ExitContextsTest {
	private static final String CONNECTOR_THREADS = "chain-exit-connector";
	private static final int WAIT_SECONDS = 15;

	private static List<Thread> connectorThreads() {
		return Thread.getAllStackTraces().keySet().stream()
			.filter(thread -> thread.getName().startsWith(CONNECTOR_THREADS)).toList();
	}

	/* The client sizes its deadline on this value and on eight addresses */
	@Example
	public void connectsWithTheTimeoutTheClientsDeadlineIsSizedOn() {
		assertThat(ExitContexts.CONNECT_TIMEOUT_MILLIS, is(10_000));
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
}
