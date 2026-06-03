/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

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

package org.unigrid.hedgehog.model.network;

import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.commons.configuration2.sync.LockMode;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.Lock;
import org.unigrid.hedgehog.model.cdi.Protected;
import org.unigrid.hedgehog.model.network.packet.Packet;

import io.netty.util.concurrent.Future;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class Topology {
	private HashSet<Node> nodes;

	@Inject
	@Getter private ChannelMap channels;

	private final Set<String> localInterfaceAddressesCache = new HashSet<>();

	@PostConstruct
	private void init() {
		try {
			java.net.NetworkInterface.getNetworkInterfaces().asIterator().forEachRemaining(ni -> {
				ni.inetAddresses().forEach(a -> {
					localInterfaceAddressesCache.add(a.getHostAddress().toLowerCase());
					localInterfaceAddressesCache.add(a.getHostName().toLowerCase());
				});
			});
			localInterfaceAddressesCache.add("127.0.0.1");
			localInterfaceAddressesCache.add("localhost");
			localInterfaceAddressesCache.add("0:0:0:0:0:0:0:1");
		} catch (Exception e) {
			log.atTrace().log("Could not warm up local address cache: {}", e.getMessage());
		}

		repopulate();
	}

	@Protected @Lock(LockMode.WRITE)
	public void clear() {
		nodes.clear();
	}

	@Protected @Lock(LockMode.READ)
	public void repopulate() {
		nodes = new HashSet<>();
		channels.clear();

		for (String address : Network.getSeeds()) {
			processSeedAddress(address);
		}
	}

	private void processSeedAddress(String address) {
		try {
			String cleanAddress = address.contains(":") ? address.split(":")[0] : address;
			if (localInterfaceAddressesCache.contains(cleanAddress.toLowerCase())) {
				return;
			}

			final Node node = Node.fromAddress(address);
			if (isLocalNode(node)) {
				return;
			}

			if (!node.isMe()) {
				addNode(node);
			}
		} catch (URISyntaxException ex) {
			log.atError().log("Invalid address format for seed node {}: {}", address, ex);
		}
	}

	private boolean isLocalNode(Node node) {
		if (node.getAddress() != null) {
			String host = node.getAddress().getHostString().toLowerCase();
			return localInterfaceAddressesCache.contains(host);
		}
		return false;
	}

	@Protected @Lock(LockMode.READ)
	public void forEach(Consumer<Node> consumer) {
		nodes.forEach(consumer);
	}

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
		if (node != null && node.getAddress() != null) {
			String nodeIp = node.getAddress().getHostString();
			if (localInterfaceAddressesCache.contains(nodeIp.toLowerCase())) {
				return false;
			}
		}

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
		topology.forEach(node -> {
			if (node.getConnection().isPresent()) {
				Node.send(packet, node, consumer);
			}
		});
	}
}






