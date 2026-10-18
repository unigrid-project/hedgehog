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

package org.unigrid.hedgehog.model.network;

import io.netty.util.concurrent.Future;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.configuration2.sync.LockMode;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.Lock;
import org.unigrid.hedgehog.model.cdi.Protected;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.GridnodeSignature;

@Slf4j
@ApplicationScoped
public class Topology {
	/* Far above any real network, but low enough that a flood of made-up keys cannot exhaust the memory of a node */
	public static final int MAX_GRIDNODES = 10_000;

	private HashSet<Node> nodes;
	private HashMap<String, Gridnode> gridnodes;

	@Inject
	@Getter private ChannelMap channels;

	@PostConstruct
	private void init() {
		repopulate();
		gridnodes = new HashMap<>();
	}

	@Protected @Lock(LockMode.WRITE)
	public void clear() {
		nodes.clear();
	}

	@Protected @Lock(LockMode.WRITE)
	public void repopulate() {
		nodes = new HashSet<>();
		channels.clear();

		for (String address : Network.getSeeds()) {
			try {
				final Node node = Node.fromAddress(address);

				if (!node.isMe()) {
					addNode(node);
				}
			} catch (URISyntaxException ex) {
				log.atError().log("Invalid address format for seed node {}: {}", address, ex);
			} catch (UnknownHostException ex) {
				log.atWarn().log("Seed node {} does not resolve: {}", address, ex.getMessage());
			}
		}
	}

	@Protected @Lock(LockMode.READ)
	public void forEach(Consumer<Node> consumer) {
		nodes.forEach(consumer);
	}

	@Protected @Lock(LockMode.READ)
	public boolean isEmpty() {
		return nodes.isEmpty();
	}

	@Protected @Lock(LockMode.WRITE)
	public void modifyNode(Node node, Consumer<Node> consumer) {
		nodes.forEach(n -> {
			if (node.equals(n)) {
				consumer.accept(n);
			}
		});
	}

	/* The address decides the hash of a node, so it has to leave the set while the address changes */
	@Protected @Lock(LockMode.WRITE)
	public void changeAddress(Node node, InetSocketAddress address) {
		nodes.stream().filter(node::equals).findFirst().ifPresent(n -> {
			nodes.remove(n);
			n.setAddress(address);
			nodes.add(n);
		});
	}

	@Protected @Lock(LockMode.READ)
	public Set<Node> cloneNodes() {
		return new HashSet(nodes);
	}

	@Protected @Lock(LockMode.READ)
	public boolean containsNode(Node node) {
		return nodes.contains(node);
	}

	@Protected @Lock(LockMode.WRITE)
	public boolean addNode(Node node) {
		if (!nodes.contains(node) && !node.isMe()) {
			return nodes.add(node);
		}

		return false;
	}

	@Protected @Lock(LockMode.WRITE)
	public boolean removeNode(Node node) {
		return nodes.remove(node);
	}

	@Protected @Lock(LockMode.READ)
	public static void sendAll(Packet packet, Topology topology, Optional<BiConsumer<Node, Future>> consumer) {
		log.atDebug().log("Send all packet " + packet.toString());
		topology.forEach(node -> {
			if (node.getConnection().isPresent()) {
				Node.send(packet, node, consumer);
			}
		});
	}

	public static void sendAllExcept(Packet packet, Topology topology, Optional<Node> excluded) {
		topology.forEach(node -> {
			if (!excluded.equals(Optional.of(node))) {
				Node.send(packet, node, Optional.empty());
			}
		});
	}

	public boolean offerGridnode(Gridnode gridnode) {
		return offerGridnode(gridnode, System.currentTimeMillis());
	}

	/* Anyone on the network can send an entry, so the cheap checks go first and the costly signature check runs
	   outside the lock, which only guards the final insert */
	public boolean offerGridnode(Gridnode gridnode, long nowMillis) {
		return GridnodeSignature.isFresh(gridnode, nowMillis) && isNewer(gridnode, findGridnode(gridnode.getId()))
			&& GridnodeSignature.verifies(gridnode) && storeVerified(gridnode);
	}

	/* The newer-than check is repeated under the lock, as the stored entry may have changed during verification */
	@Protected @Lock(LockMode.WRITE)
	public boolean storeVerified(Gridnode gridnode) {
		final Optional<Gridnode> stored = Optional.ofNullable(gridnodes.get(gridnode.getId()));

		if (isNewer(gridnode, stored) && (stored.isPresent() || gridnodes.size() < MAX_GRIDNODES)) {
			gridnodes.put(gridnode.getId(), gridnode);
			return true;
		}

		return false;
	}

	private static boolean isNewer(Gridnode gridnode, Optional<Gridnode> stored) {
		return stored.map(held -> gridnode.getTimestamp() > held.getTimestamp()).orElse(true);
	}

	@Protected @Lock(LockMode.READ)
	public Optional<Gridnode> findGridnode(String id) {
		return Optional.ofNullable(gridnodes.get(id));
	}

	@Protected @Lock(LockMode.WRITE)
	public void purgeGridnodes(long nowMillis, Optional<String> keep) {
		gridnodes.values().removeIf(gridnode -> GridnodeSignature.isExpired(gridnode, nowMillis)
			&& !keep.equals(Optional.of(gridnode.getId())));
	}

	@Protected @Lock(LockMode.READ)
	public Set<Gridnode> cloneGridnode() {
		return new HashSet<>(gridnodes.values());
	}
}
