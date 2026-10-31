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

package org.unigrid.hedgehog.model.producer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.service.chain.ChainTransport;
import org.unigrid.hedgehog.service.chain.NettyChainTransport;
import org.unigrid.hedgehog.service.chain.ProxySettingsSource;

@Slf4j
@ApplicationScoped
public class ChainProducer {
	private static final int MAX_REPORTED = 256;
	private static final String REFUSED = "The communication spork is refused, so the proxy stays off: {}";

	private final Set<String> reported = ConcurrentHashMap.newKeySet();

	@Inject
	private SporkDatabase sporkDatabase;

	@Inject
	private Topology topology;

	@Produces @Singleton
	public ChainTransport chainTransport() {
		return new NettyChainTransport(topology::cloneNodes, topology::cloneGridnode);
	}

	@Produces @Singleton
	public ProxySettingsSource proxySettingsSource() {
		return this::proxySettings;
	}

	/* Nothing validates a spork on receipt, so parameters that its checks or ProxySettings refuse turn the proxy
	   off instead of failing */
	Optional<ProxySettings> proxySettings() {
		return Optional.ofNullable(sporkDatabase.getCommunicationSpork())
			.<CommunicationSpork.SporkData>map(CommunicationSpork::getData)
			.flatMap(this::settingsOf);
	}

	private Optional<ProxySettings> settingsOf(final CommunicationSpork.SporkData data) {
		try {
			data.validate();

			return Optional.of(new ProxySettings(data.getGroupSize(), data.getReorganizePeriodSeconds(),
				data.getChainsPerGroup(), data.getClientsPerChain(), data.getChainCapacityBytes(),
				data.getRetentionSeconds(), data.getMaxEntryBytes(), data.getSessionIdleSeconds(),
				data.getCommunicationFlags()));
		} catch (IllegalArgumentException ex) {
			reportOnce(String.valueOf(ex.getMessage()));
			return Optional.empty();
		}
	}

	/* The proxy asks again every time it needs its settings, and a spork that stays as it is would fill the log */
	private void reportOnce(final String refusal) {
		if (reported.size() < MAX_REPORTED && reported.add(refusal)) {
			log.atWarn().addArgument(refusal).log(REFUSED);
		}
	}
}
