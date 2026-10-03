/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.producer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.unigrid.hedgehog.command.option.GridnodeOptions;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;
import org.unigrid.hedgehog.service.storage.FragmentKeeper;
import org.unigrid.hedgehog.service.storage.FragmentTransport;
import org.unigrid.hedgehog.service.storage.NettyFragmentTransport;

@ApplicationScoped
public class StorageProducer {
	private static final String FRAGMENT_DIRECTORY = "fragments";

	@Inject
	private ApplicationDirectory applicationDirectory;

	@Inject
	private SporkDatabase sporkDatabase;

	@Inject
	private Topology topology;

	@Produces @Singleton
	public FragmentStore fragmentStore() throws IOException {
		final Path root = applicationDirectory.getUserDataDir().resolve(FRAGMENT_DIRECTORY);

		return new FragmentStore(root, Clock.systemUTC());
	}

	@Produces @Singleton
	public FragmentKeeper fragmentKeeper(final FragmentStore store) {
		return new FragmentKeeper(store, this::storageSpork);
	}

	@Produces @Singleton
	public GridnodeDirectory gridnodeDirectory() {
		return new TopologyGridnodeDirectory(topology::cloneGridnode, GridnodeOptions::getGridnodeKey);
	}

	@Produces @Singleton
	public FragmentTransport fragmentTransport(final PendingRequests pendingRequests) {
		return new NettyFragmentTransport(topology::cloneNodes, pendingRequests);
	}

	/* Nothing validates a spork on receipt, so parameters that break the layout disable storage instead */
	Optional<StorageSpork.SporkData> storageSpork() {
		return Optional.ofNullable(sporkDatabase.getStorageSpork())
			.<StorageSpork.SporkData>map(StorageSpork::getData)
			.filter(StorageProducer::isValid);
	}

	private static boolean isValid(final StorageSpork.SporkData data) {
		try {
			data.validate();
			return true;
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}
}
