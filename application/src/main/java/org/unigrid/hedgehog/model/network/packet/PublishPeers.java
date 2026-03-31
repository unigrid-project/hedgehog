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

 package org.unigrid.hedgehog.model.network.packet;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.unigrid.hedgehog.model.network.Node;

public class PublishPeers extends Packet implements Serializable {

    private static final long serialVersionUID = 1L;
    private final Set<Node> nodes = new HashSet<>();

    public PublishPeers() {
        super(Type.PUBLISH_PEERS);
    }

    public PublishPeers(Collection<Node> nodes) {
        this();
        if (nodes != null) {
            this.nodes.addAll(nodes);
        }
    }

    public Set<Node> getNodes() {
        return nodes;
    }

    public void addNode(Node node) {
        if (node != null) nodes.add(node);
    }

    public void addNodes(Collection<Node> nodes) {
        if (nodes != null) this.nodes.addAll(nodes);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    @Override
    public String toString() {
        return "PublishPeers{nodes=" + nodes.size() + '}';
    }
}