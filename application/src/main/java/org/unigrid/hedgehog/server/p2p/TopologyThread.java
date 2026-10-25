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

package org.unigrid.hedgehog.server.p2p;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.GridnodeAnnouncer;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Dialed;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.handler.RemoteAddress;

@Slf4j
public class TopologyThread extends Thread {
	public static final int BASE_SECONDS_BETWEEN_RECONNECTS = 30;
	public static final int ADDITIONAL_SECONDS_BETWEEN_RECONNECTS_PER_NODE = 3;
	public static final int MAX_OUTBOUND = 32;

	private Object lock = new Object();
	private boolean run = true;

	@RequiredArgsConstructor
	public class NodeConnectionHandler implements Consumer<Node> {
		private final Topology topology;

		/* The nodes that an operator configured, as seeds or over REST, whose connections are marked so */
		private final Set<Node> configured;

		public NodeConnectionHandler(Topology topology) {
			this(topology, Set.of());
		}

		@Override
		public void accept(Node node) {
			log.atTrace().log("Handling connection {}", node);
			node.getConnection().filter(c -> !c.getChannel().isActive()).ifPresent(c -> drop(node, c));

			try {
				if (!node.getConnection().isPresent()) {
					final Dialed why = configured.contains(node) ? Dialed.CONFIGURED : Dialed.CHOSEN;
					final P2PClient client = new P2PClient(node.getAddress().getHostString(),
						node.getAddress().getPort(), why
					);

					topology.modifyNode(node, n -> {
						n.setConnection(Optional.of(client));
						topology.getChannels().set(client.getChannel(), n);
					});
				}
			} catch (ExecutionException | InterruptedException | TimeoutException | RuntimeException ex) {

				log.atWarn().log("Node connection to {} failed", node, ex);

				node.getConnection().ifPresent(connection -> {
					connection.closeDirty();
					topology.getChannels().remove(connection.getChannel());
				});

				forget(node);
			}
		}

		/* A node that cannot be dialed but holds a stream that it opened to this one is still reached on that, and
		   one that an operator configured is the operator's to remove: it is dialed again in the next round */
		private void forget(Node node) {
			if (configured.contains(node) || node.getInbound().filter(inbound -> inbound.getChannel().isActive())
				.isPresent()) {
				topology.modifyNode(node, n -> n.setConnection(Optional.empty()));
			} else {
				topology.removeNode(node);
				log.atTrace().log("Removed node {} from topology", node);
			}
		}

		private void drop(Node node, Connection connection) {
			log.atDebug().log("Dropping closed connection to {}", node);
			connection.closeDirty();
			topology.getChannels().remove(connection.getChannel());
			topology.modifyNode(node, n -> n.setConnection(Optional.empty()));
		}
	}

	/* The nodes that peers tell of, and that say hello, are anyone's to make, so no more of them than the node dials
	   draw out a round */
	static long reconnectionTime(long nodes) {
		return (BASE_SECONDS_BETWEEN_RECONNECTS
			+ (ADDITIONAL_SECONDS_BETWEEN_RECONNECTS_PER_NODE * (Math.min(nodes, MAX_OUTBOUND) + 1)))
			* 1000;
	}

	/* The dialed nodes are looked after first, so a closed connection is dropped, and then the nodes that an operator
	   configured are dialed, and other nodes in a random order until the node dialed MAX_OUTBOUND of them: peers choose
	   what the topology holds, and a node that connected to all of it would hand them its sockets and threads. Only a
	   connection that this node dialed counts, as anyone can connect to it as often as they like. Of the nodes that no
	   operator configured, one is dialed in each network group, which the address dialed decides: anyone can tell a
	   node of as many addresses as they like, and those that it dials are the peers whose checks it pays the most of
	   (PublishSporkChannelHandler). */
	static List<Node> inTurn(Set<Node> nodes, Set<Node> configured) {
		final List<Node> dialed = nodes.stream().filter(TopologyThread::isDialed).toList();
		final List<Node> chosen = dialed.stream().filter(node -> !configured.contains(node)).toList();
		final Set<String> groups = chosen.stream().map(TopologyThread::groupOf).collect(Collectors.toSet());
		final List<Node> others = new ArrayList<>(nodes.stream().filter(node -> !isDialed(node)).toList());

		Collections.shuffle(others);

		final Stream<Node> diverse = others.stream().filter(node -> !configured.contains(node))
			.filter(node -> groups.add(groupOf(node))).limit(Math.max(0, MAX_OUTBOUND - chosen.size()));

		return Stream.of(dialed.stream(), others.stream().filter(configured::contains), diverse)
			.flatMap(Function.identity()).toList();
	}

	private static boolean isDialed(Node node) {
		return node.getConnection().map(Connection::getChannel).flatMap(Dialed::of).isPresent();
	}

	private static String groupOf(Node node) {
		return RemoteAddress.groupOf(node.getAddress());
	}

	@Override
	public void run() {
		CDIUtil.resolveAndRun(Topology.class, topology -> {
			while (run) {
				/* If we have no connections, we try the seed nodes again! */
				if (topology.isEmpty()) {
					topology.repopulate();
				}

				topology.addSeeds();

				final Set<Node> configured = topology.cloneConfigured();

				CDIUtil.resolveAndRun(GridnodeAnnouncer.class, GridnodeAnnouncer::maintain);

				/* Every node first, as one that said hello is handed to the round only while it has a slot
				   to be dialed in */
				topology.letGoOfClosedStreams();

				final Set<Node> nodes = topology.cloneNodes();
				inTurn(nodes, configured).forEach(new NodeConnectionHandler(topology, configured));

				synchronized (lock) {
					try {
						lock.wait(reconnectionTime(nodes.size()));
					} catch (InterruptedException ex) {
						return; /* At this point we know we are done and can just bail out */
					}
				}
			}
		});
	}

	public void exit() {
		synchronized (lock) {
			run = false;
			lock.notify();
		}
	}
}
