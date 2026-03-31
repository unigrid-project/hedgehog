 package org.unigrid.hedgehog.model;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Minimal nätverkskonfiguration för Topology och Network.
 */
@ApplicationScoped
public class NetOptions {

    private int port = 9333; // standardport (ändra om ditt projekt använder annan)

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

}