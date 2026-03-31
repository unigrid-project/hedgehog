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

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.netty.util.concurrent.Future;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.model.cdi.Lock;
import org.unigrid.hedgehog.model.cdi.LockMode;
import org.unigrid.hedgehog.model.cdi.Protected;
import org.unigrid.hedgehog.model.network.packet.Packet;

@ApplicationScoped
public class Topology {

    private static final Logger log = LoggerFactory.getLogger(Topology.class);

    private HashSet<Node> nodes = new HashSet<>();

    @Inject
    private ChannelMap channels;

    @PostConstruct
    private void init() {
        repopulate();
    }

    @Protected
    @Lock(LockMode.WRITE)
    public void clear() {
        nodes.clear();
    }

    @Protected
    @Lock(LockMode.READ)
    public void repopulate() {
        nodes.clear();
        channels.clear();

        for (String address : Network.getSeeds()) {
            try {
                final Node node = Node.fromAddress(address);
                if (!node.isMe(NetOptions.getPort())) {
                    addNode(node);
                }
            } catch (URISyntaxException ex) {
                log.error("Invalid address format for seed node {}", address, ex);
            }
        }
    }

    @Protected
    @Lock(LockMode.READ)
    public void forEach(Consumer<Node> consumer) {
        nodes.forEach(consumer);
    }

    @Protected
    @Lock(LockMode.WRITE)
    public boolean addNode(Node node) {
        if (!nodes.contains(node) && !node.isMe(NetOptions.getPort())) {
            return nodes.add(node);
        }
        return false;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    @Protected
    @Lock(LockMode.READ)
    public Set<Node> cloneNodes() {
        return new HashSet<>(nodes);
    }

    @Protected
    @Lock(LockMode.READ)
    public boolean containsNode(Node node) {
        return nodes.contains(node);
    }

    @Protected
    @Lock(LockMode.WRITE)
    public boolean removeNode(Node node) {
        return nodes.remove(node);
    }

    @Protected
    @Lock(LockMode.WRITE)
    public void modifyNode(Node node, java.util.function.Consumer<Node> modifier) {
        if (nodes.contains(node)) {
            modifier.accept(node);
        }
    }

    @Protected
    @Lock(LockMode.READ)
    public static void sendAll(Packet packet, Topology topology, Optional<BiConsumer<Node, Future<?>>> consumer) {
        topology.forEach(node -> {
            if (node.getConnection().isPresent()) {
                Node.send(packet, node, consumer);
            }
        });
    }
}