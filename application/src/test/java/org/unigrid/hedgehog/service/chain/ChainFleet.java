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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* Gridnodes that share one clock, one topology and one set of settings, each with its own host */
final class ChainFleet {
	static final long NOW = 1_750_000_000_000L;

	final AtomicLong clock = new AtomicLong(NOW);
	final AtomicReference<Optional<ProxySettings>> settings = new AtomicReference<>(Optional.of(ProxySettings.DEFAULTS));
	final List<Gridnode> gridnodes = new CopyOnWriteArrayList<>();
	private final Map<String, ChainHost> hosts = new LinkedHashMap<>();

	ChainFleet(final int size) {
		IntStream.range(0, size).forEach(number -> gridnodes.add(gridnode("gn-" + number, Gridnode.Status.ACTIVE)));
	}

	static Gridnode gridnode(final String id, final Gridnode.Status status) {
		return Gridnode.builder().id(id).status(status).hostName(id + ":1").build();
	}

	static ProxySettings settings(final int groupSize, final int chainsPerGroup, final int clientsPerChain,
		final int retentionSeconds, final int flags) {

		return new ProxySettings(groupSize, 3600, chainsPerGroup, clientsPerChain, 32L * 1024 * 1024,
			retentionSeconds, 16 * 1024, 300, flags);
	}

	ChainHost host(final String id) {
		return hosts.computeIfAbsent(id, own -> new ChainHost(settings::get,
			new TopologyGridnodeDirectory(() -> gridnodes, () -> own), clock::get));
	}

	List<String> activeIds() {
		return gridnodes.stream().filter(gridnode -> gridnode.getStatus() == Gridnode.Status.ACTIVE)
			.map(Gridnode::getId).toList();
	}

	GroupView view() {
		return new GroupView(settings.get().orElseThrow(), clock.get(), activeIds());
	}

	void refreshAll() {
		new ArrayList<>(hosts.values()).forEach(ChainHost::refresh);
	}
}
