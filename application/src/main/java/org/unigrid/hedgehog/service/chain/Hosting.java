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

import io.netty.channel.Channel;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.ChainLimits;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.Epoch;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* What a node hosts at one moment, replaced as a whole when the topology, the time or the settings change.
   The chains keep the order of their indexes. The peers are the other members of the node's group; they follow
   from the basis but are kept, so that a refresh which reuses the hosting can look up their streams without
   ranking the groups again. The retired chains are those of earlier hostings that this one does not want, in the
   order they retired, kept with their entries for as long as the host keeps them. */
record Hosting(Optional<Basis> basis, OptionalInt group, List<String> peers, Map<ChainId, HostedChain> current,
	Map<ChainId, HostedChain> retired) {

	static final Hosting OFF = new Hosting(Optional.empty(), OptionalInt.empty(), List.of(), Map.of(), Map.of());

	record Basis(ProxySettings settings, Epoch epoch, Set<String> activeIds, Optional<String> own) {
		/* Everything the groups and chains of a hosting follow from, so an equal basis needs no new ranking */
	}

	Optional<ProxySettings> settings() {
		return basis.map(Basis::settings);
	}

	boolean isBuiltFrom(final Basis other) {
		return basis.filter(other::equals).isPresent();
	}

	/* The chains this hosting holds, the current epoch's first. The retired ones keep their entries and are read
	   by id, but take no new clients and are not among the chains the host is hosting. */
	Stream<HostedChain> all() {
		return Stream.concat(current.values().stream(), retired.values().stream());
	}

	Optional<HostedChain> find(final ChainId id) {
		return Optional.ofNullable(current.getOrDefault(id, retired.get(id)));
	}

	/* The chain a writer is attached to, else the one with the fewest client feeds, the lowest index among equals,
	   but a chain that takes no more of the writer only when no other does. A named chain is the only candidate,
	   because the writer joined another member's chain and attaches to the same one here; a retired chain takes
	   no new client, so it is not one. */
	Optional<HostedChain> placement(final WriterId writer, final Optional<ChainId> named) {
		if (named.isPresent()) {
			return Optional.ofNullable(current.get(named.get()));
		}

		return current.values().stream().filter(hosted -> hosted.isAttached(writer)).findFirst()
			.or(() -> current.values().stream().min(byRoom(writer)));
	}

	private static Comparator<HostedChain> byRoom(final WriterId writer) {
		return Comparator.comparing((HostedChain hosted) -> !hosted.canTake(writer))
			.thenComparingInt(HostedChain::clientCount);
	}

	/* The writers a stream is attached as, over all chains */
	long clientsOn(final Channel stream) {
		return all().mapToLong(hosted -> hosted.clientsOn(stream)).sum();
	}

	/* A chain this hosting no longer holds is gone with its attachments, so the held and retired ones are searched */
	void detachAll(final Channel stream) {
		all().forEach(hosted -> hosted.detachAll(stream));
	}

	/* A chain keeps its entries as long as its id and its limits stay what they were */
	Optional<HostedChain> reusable(final ChainId id, final ChainLimits limits) {
		return find(id).filter(hosted -> hosted.getChain().limits().equals(limits));
	}
}
