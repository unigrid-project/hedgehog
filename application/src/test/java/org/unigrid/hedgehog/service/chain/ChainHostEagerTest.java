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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import org.jboss.weld.environment.se.WeldContainer;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.cdi.EagerExtension;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

@WeldSetup(value = { ChainHost.class, ChainHostEagerTest.Wiring.class }, extensions = { EagerExtension.class },
	scan = false)
public class ChainHostEagerTest extends BaseMockedWeldTest {
	private static final int WAIT_SECONDS = 10;
	private static final AtomicInteger HOSTS_CREATED = new AtomicInteger();
	private static final Set<Thread> TICKERS = ConcurrentHashMap.newKeySet();
	private static final List<Gridnode> GRIDNODES = new ChainFleet(20).gridnodes;

	@Inject private ChainHost host;

	@ApplicationScoped
	public static class Wiring {
		@Produces
		public ProxySettingsSource settings() {
			return () -> {
				TICKERS.add(Thread.currentThread());
				return Optional.of(ProxySettings.DEFAULTS);
			};
		}

		@Produces
		public GridnodeDirectory directory() {
			return new TopologyGridnodeDirectory(() -> GRIDNODES, () -> "gn-3");
		}

		/* Needed to create the host, so it counts the hosts that were created */
		@Produces
		public ChainTransport transport() {
			HOSTS_CREATED.incrementAndGet();
			return peer -> Optional.empty();
		}
	}

	@Example
	public void startsHostingOnItsOwnOnceTheContainerIsUpAndStopsWhenItIsShutDown() {
		assertThat(HOSTS_CREATED.get(), is(1));

		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> !host.hostedChains().isEmpty());

		assertThat(host.hostedChains().size(), is(4));
		assertThat(TICKERS, hasSize(1));

		final Thread ticker = TICKERS.iterator().next();

		assertThat(ticker.isDaemon(), is(true));

		WeldContainer.instance(ChainHostEagerTest.class.getSimpleName()).shutdown();

		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> !ticker.isAlive());
	}
}
