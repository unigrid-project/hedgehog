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

package org.unigrid.hedgehog.command;

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import me.alexpanov.net.FreePortFinder;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeContainer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.NamedCDIProvider;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.cdi.EagerExtension;
import org.unigrid.hedgehog.model.geo.CountryLookup;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;
import org.unigrid.hedgehog.proxy.socks.Tunnel;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.server.rest.RestServer;
import org.unigrid.hedgehog.server.rest.RestToken;
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
public class DaemonStartTest extends BaseMockedWeldTest {
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

	/* The topology thread of the P2P server asks for the container while it starts, before the harness has named it */
	@BeforeContainer
	private static void nameTheContainer() {
		NamedCDIProvider.NAME_REFERENCE.set(DaemonStartTest.class.getSimpleName());
	}

	/* The S3 endpoint is on unless told otherwise, so it is given a port of its own as well */
	@BeforeContainer
	private static void parseTheOptions() {
		final int netPort = FreePortFinder.findFreeLocalPort();
		final int restPort = FreePortFinder.findFreeLocalPort(netPort + 1);
		final int s3Port = FreePortFinder.findFreeLocalPort(restPort + 1);

		OptionsCli.parse(List.of("daemon"), "--nethost", "localhost", "--netport", String.valueOf(netPort),
			"--resthost", "localhost", "--restport", String.valueOf(restPort), "--resttoken", RestToken.generate(),
			"--no-seeds", "--s3host", "localhost", "--s3port", String.valueOf(s3Port));
	}

	/* Asks the context for the instance it holds, which creates none */
	private boolean isCreated(final Class<?> type) {
		final Bean<?> bean = beanManager.resolve(beanManager.getBeans(type));

		return Objects.nonNull(beanManager.getContext(bean.getScope()).get(bean));
	}

	@Example
	public void shouldStartTheServersOfThisReleaseAlone() {
		assertThat(isCreated(P2PServer.class), is(true));
		assertThat(isCreated(RestServer.class), is(true));
		assertThat(PARTS_OF_THE_PREVIEW.stream().filter(this::isCreated).toList(), is(empty()));
		assertThat(BUILT_BY_THE_PREVIEW.stream().filter(this::isCreated).toList(), is(empty()));
	}

	@Example
	public void shouldStartNoThreadOfThePreview() {
		final List<String> started = Thread.getAllStackTraces().keySet().stream().map(Thread::getName)
			.filter(THREADS_OF_THE_PREVIEW::contains).toList();

		assertThat(started, is(empty()));
	}
}
