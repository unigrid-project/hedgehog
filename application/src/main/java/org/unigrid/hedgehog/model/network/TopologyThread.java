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

import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.cdi.CDIUtil;

public class TopologyThread extends Thread {

    private static final Logger log =
            LoggerFactory.getLogger(TopologyThread.class);

    public static final int BASE_SECONDS_BETWEEN_RECONNECTS = 30;
    public static final int ADDITIONAL_SECONDS_BETWEEN_RECONNECTS_PER_NODE = 3;

    private final Object lock = new Object();
    private volatile boolean run = true;

    private static class NodeConnectionHandler implements Consumer<Node> {

        private final Topology topology;

        private NodeConnectionHandler(Topology topology) {
            this.topology = topology;
        }

        @Override
        public void accept(Node node) {

            log.trace("Handling connection {}", node);

            try {

                if (node.getConnection().isEmpty()) {

                    P2PClient client = new P2PClient(
                            node.getAddress().getHostName(),
                            node.getAddress().getPort()
                    );

                    topology.modifyNode(node, n -> {
                        n.setConnection(Optional.of(client));

                        CDIUtil.resolveAndRun(ChannelMap.class,
                                channelMap ->
                                        channelMap.set(client.getChannel(), n)
                        );
                    });
                }

            } catch (Exception ex) {   // ← FIX: inga unreachable exceptions

                log.warn("Node connection to {} failed", node, ex);

                node.getConnection().ifPresent(connection -> {

                    connection.closeDirty();

                    CDIUtil.resolveAndRun(ChannelMap.class,
                            channelMap ->
                                    channelMap.remove(connection.getChannel())
                    );
                });

                topology.removeNode(node);
                log.trace("Removed node {} from topology", node);
            }
        }
    }

    private long getReconnectionTime(long connections) {
        return (BASE_SECONDS_BETWEEN_RECONNECTS
                + (ADDITIONAL_SECONDS_BETWEEN_RECONNECTS_PER_NODE * (connections + 1)))
                * 1000L;
    }

    @Override
    public void run() {

        CDIUtil.resolveAndRun(Topology.class, topology -> {

            while (run) {

                if (topology.isEmpty()) {
                    topology.repopulate();
                }

                Set<Node> nodes = topology.cloneNodes();
                nodes.forEach(new NodeConnectionHandler(topology));

                synchronized (lock) {
                    try {
                        lock.wait(getReconnectionTime(nodes.size()));
                    } catch (InterruptedException ignored) {
                        return;
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