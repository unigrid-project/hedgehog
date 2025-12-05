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

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.ChainLimits;
import org.unigrid.hedgehog.proxy.group.Epoch;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* What a node hosts at one moment, replaced as a whole when the topology, the time or the settings change.
   The chains keep the order of their indexes. */
record Hosting(Optional<Basis> basis, OptionalInt group, Map<ChainId, HostedChain> current) {
	static final Hosting OFF = new Hosting(Optional.empty(), OptionalInt.empty(), Map.of());

	record Basis(ProxySettings settings, Epoch epoch, Set<String> activeIds, Optional<String> own) {
		/* Everything the groups and chains of a hosting follow from, so an equal basis needs no new ranking */
	}

	Optional<ProxySettings> settings() {
		return basis.map(Basis::settings);
	}

	boolean isBuiltFrom(final Basis other) {
		return basis.filter(other::equals).isPresent();
	}

	Optional<HostedChain> find(final ChainId id) {
		return Optional.ofNullable(current.get(id));
	}

	/* A chain keeps its entries as long as its id and its limits stay what they were */
	Optional<HostedChain> reusable(final ChainId id, final ChainLimits limits) {
		return find(id).filter(hosted -> hosted.getChain().limits().equals(limits));
	}
}
