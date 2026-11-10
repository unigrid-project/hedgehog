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

package org.unigrid.hedgehog.server;

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.DefaultNodeHook;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.cdi.EagerExtension;
import org.unigrid.hedgehog.model.geo.CountryLookup;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;
import org.unigrid.hedgehog.proxy.socks.Tunnel;
import org.unigrid.hedgehog.server.s3.S3Gateway;
import org.unigrid.hedgehog.server.socks.SocksServer;
import org.unigrid.hedgehog.service.chain.ChainExit;
import org.unigrid.hedgehog.service.chain.ChainHost;
import org.unigrid.hedgehog.service.chain.ChainTransport;
import org.unigrid.hedgehog.service.ledger.LedgerService;
import org.unigrid.hedgehog.service.storage.FragmentKeeper;
import org.unigrid.hedgehog.service.storage.FragmentTransport;
import org.unigrid.hedgehog.service.storage.GroupRepairer;
import org.unigrid.hedgehog.service.storage.RepairService;
import org.unigrid.hedgehog.service.storage.StorageService;

/* Every bean of the node in the container that a daemon run without --preview starts, on ports of its own and with no
   seeds. Each test class has a JVM of its own, so the threads here are those of this container alone. */
@WeldSetup(value = { }, extensions = { EagerExtension.class })
@AddLifecycleHook(value = DefaultNodeHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BaseDefaultNodeTest extends BaseMockedWeldTest {
	private static final Set<String> THREADS_OF_THE_PREVIEW = Set.of("storage-repair", "chain-host", "chain-delivery",
		"chain-exit", "chain-client", "s3-start", "lifecycle-sweeper", "namespace-maintenance");

	private static final List<Class<?>> PARTS_OF_THE_PREVIEW = List.of(LedgerService.class, S3Gateway.class,
		RepairService.class, ChainHost.class, ChainExit.class, SocksServer.class);

	/* What the parts of the preview build when they start: the tunnel with the GeoIP database, and the storage with
	   its directories */
	private static final List<Class<?>> BUILT_BY_THE_PREVIEW = List.of(Tunnel.class, CountryLookup.class,
		ChainTransport.class, StorageService.class, FragmentTransport.class, FragmentKeeper.class,
		GroupRepairer.class, FragmentStore.class);

	@Inject private BeanManager beanManager;

	/* Asks the context for the instance it holds, which creates none */
	protected boolean isCreated(final Class<?> type) {
		final Bean<?> bean = beanManager.resolve(beanManager.getBeans(type));

		return Objects.nonNull(beanManager.getContext(bean.getScope()).get(bean));
	}

	/* The parts of the preview, and what they build, that the container holds an instance of */
	protected List<Class<?>> createdOfThePreview() {
		return Stream.concat(PARTS_OF_THE_PREVIEW.stream(), BUILT_BY_THE_PREVIEW.stream()).filter(this::isCreated)
			.toList();
	}

	protected List<String> threadsOfThePreview() {
		return Thread.getAllStackTraces().keySet().stream().map(Thread::getName)
			.filter(THREADS_OF_THE_PREVIEW::contains).toList();
	}
}
