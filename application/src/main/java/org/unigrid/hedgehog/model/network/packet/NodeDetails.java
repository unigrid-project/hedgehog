 package org.unigrid.hedgehog.model.network.packet;

import java.io.Serializable;

public class NodeDetails extends Packet implements Serializable {

    private boolean protocol;
    private boolean version;

    public NodeDetails() {
        super(Type.PUBLISH_NODE_DETAILS);
    }

    public boolean isProtocol() {
        return protocol;
    }

    public void setProtocol(boolean protocol) {
        this.protocol = protocol;
    }

    public boolean isVersion() {
        return version;
    }

    public void setVersion(boolean version) {
        this.version = version;
    }
}