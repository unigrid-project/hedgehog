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

import io.netty.channel.Channel;
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
import java.util.function.Predicate;
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

	/* Many more peers than a node connects to, but low enough that a flood of addresses cannot fill its memory. The
	   seeds are let in past it, so a topology that others filled still holds a way back to the network. */
	public static final int MAX_NODES = 1_000;

	private HashSet<Node> nodes;
	private HashMap<String, Gridnode> gridnodes;

	/* The seeds and the nodes that the operator added over REST, which are dialed whatever peers tell */
	private final Set<Node> configured = new HashSet<>();

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
		addSeeds();
	}

	/* The seeds stay in the topology whatever else it holds: one that only peers had filled would leave a node with
	   nothing but what they chose to tell it. Each is configured as the operator's, also when a peer told of it first.
	   Gives the seeds that resolve. */
	@Protected @Lock(LockMode.WRITE)
	public Set<Node> addSeeds() {
		final Set<Node> seeds = new HashSet<>();

		for (String address : Network.getSeeds()) {
			try {
				final Node node = Node.fromAddress(address);

				if (addConfigured(node) != Configured.REFUSED) {
					seeds.add(node);
				}
			} catch (URISyntaxException ex) {
				log.atError().log("Invalid address format for seed node {}: {}", address, ex);
			} catch (UnknownHostException ex) {
				log.atWarn().log("Seed node {} does not resolve: {}", address, ex.getMessage());
			}
		}

		return seeds;
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
		if (nodes.size() < MAX_NODES && !node.getAddress().isUnresolved() && !nodes.contains(node) && !node.isMe()) {
			return nodes.add(node);
		}

		return false;
	}

	/* What configuring a node did: it was added, it was known and is configured from now on, it was configured before,
	   or it is no node to dial */
	public enum Configured {
		ADDED, PROMOTED, BEFORE, REFUSED
	}

	/**
	* Configures a node that the operator gives, as a seed or over REST, which is let in past MAX_NODES and dialed
	* whatever peers tell. A node that a peer told of first, as an attacker may to keep it from being the operator's, is
	* configured all the same, and a connection that was dialed to it is one to a configured node from then on: nothing
	* that a peer sends makes a node configured, or less so.
	*/
	@Protected @Lock(LockMode.WRITE)
	public Configured addConfigured(Node node) {
		if (node.getAddress().isUnresolved() || node.isMe()) {
			return Configured.REFUSED;
		}

		final boolean isNew = configured.add(node);
		final Optional<Node> known = nodes.stream().filter(node::equals).findFirst();

		if (known.isEmpty()) {
			nodes.add(node);
			return Configured.ADDED;
		}

		known.get().getConnection().map(Connection::getChannel).ifPresent(Dialed::configure);
		return isNew ? Configured.PROMOTED : Configured.BEFORE;
	}

	@Protected @Lock(LockMode.READ)
	public Set<Node> cloneConfigured() {
		return new HashSet<>(configured);
	}

	@Protected @Lock(LockMode.WRITE)
	public boolean removeNode(Node node) {
		return nodes.remove(node);
	}

	/* A stream that a node opened to this one is let go once it closed, and with it a node known by that stream alone:
	   one that no operator configured and that this node holds no connection to */
	@Protected @Lock(LockMode.WRITE)
	public void letGoOfClosedStreams() {
		nodes.removeIf(node -> node.getConnection().isEmpty() && !configured.contains(node)
			&& node.getInbound().filter(inbound -> !inbound.getChannel().isActive()).isPresent());
		nodes.forEach(node -> node.setInbound(node.getInbound().filter(inbound -> inbound.getChannel().isActive())));
	}

	/* A node that the operator removes, which is no longer configured either */
	@Protected @Lock(LockMode.WRITE)
	public boolean removeConfigured(Node node) {
		configured.remove(node);
		return nodes.remove(node);
	}

	@Protected @Lock(LockMode.READ)
	public static void sendAll(Packet packet, Topology topology, Optional<BiConsumer<Node, Future>> consumer) {
		sendAll(packet, topology, channel -> true, consumer);
	}

	/* Only to the nodes whose stream the predicate takes, for a packet that not every node could read */
	@Protected @Lock(LockMode.READ)
	public static void sendAll(Packet packet, Topology topology, Predicate<Channel> recipients,
		Optional<BiConsumer<Node, Future>> consumer) {

		log.atDebug().log("Send all packet " + packet.toString());
		topology.forEach(node -> {
			if (node.relay().map(Connection::getChannel).filter(recipients).isPresent()) {
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
