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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import org.jboss.weld.environment.se.WeldContainer;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.cdi.EagerExtension;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* Nothing here asks the container for the exit, so whatever runs is what the container started on its own. Each test
   class has a JVM of its own, so the threads of this container are the only ones of their names. */
@WeldSetup(value = { ChainExit.class, ChainExitEagerTest.Wiring.class }, extensions = { EagerExtension.class },
	scan = false)
public class ChainExitEagerTest extends BaseMockedWeldTest {
	private static final int WAIT_SECONDS = 10;
	private static final List<Object> LISTENERS = new CopyOnWriteArrayList<>();

	/* The harness starts the container for the first bean a test asks for, which must not be the exit */
	@Inject private ProxySettingsSource settings;

	@ApplicationScoped
	public static class Wiring {
		@Produces
		public ProxySettingsSource settings() {
			return () -> Optional.of(ProxySettings.DEFAULTS);
		}

		@Produces
		public GridnodeDirectory directory() {
			return new TopologyGridnodeDirectory(() -> new ChainFleet(20).gridnodes, () -> "gn-3");
		}

		/* A host that keeps the listeners the exit registers with it */
		@Produces
		public ChainHost host(final ProxySettingsSource settings, final GridnodeDirectory directory) {
			return new ChainHost(settings, directory, peer -> Optional.empty()) {
				@Override
				public void addEntryListener(final EntryListener listener) {
					LISTENERS.add(listener);
				}

				@Override
				public void addGapListener(final GapListener listener) {
					LISTENERS.add(listener);
				}
			};
		}
	}

	private static List<Thread> threads(final Predicate<String> named) {
		return Thread.getAllStackTraces().keySet().stream().filter(thread -> named.test(thread.getName())).toList();
	}

	/* The connector and the lookups get threads of their own once a relay needs them, and none before, not even to
	   shut down */
	@Example
	public void startsWithTheContainerTickingOnADaemonOfItsOwnAloneAndStopsWithIt() {
		final List<Thread> tickers = threads("chain-exit"::equals);

		assertThat(LISTENERS, hasSize(2));
		assertThat(LISTENERS.get(0), instanceOf(EntryListener.class));
		assertThat(LISTENERS.get(1), instanceOf(GapListener.class));
		assertThat(tickers, hasSize(1));
		assertThat(tickers.stream().map(Thread::isDaemon).toList(), contains(true));
		assertThat(threads(name -> name.startsWith("chain-exit-")), is(empty()));

		WeldContainer.instance(ChainExitEagerTest.class.getSimpleName()).shutdown();

		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> !tickers.get(0).isAlive());
		assertThat(threads(name -> name.startsWith("chain-exit-")), is(empty()));
	}
}
