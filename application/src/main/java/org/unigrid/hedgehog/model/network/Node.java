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
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Future;
import jakarta.ws.rs.core.UriBuilder;
import org.unigrid.hedgehog.model.network.packet.Packet;

import java.net.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Node {

    private static final Logger log = LoggerFactory.getLogger(Node.class);

    public static final int DEFAULT_PORT = 9333;

    private InetSocketAddress address;

    @JsonIgnore
    private Optional<Connection> connection = Optional.empty();

    private Details details = new Details();

    private long nsPing;

    public Node() {}

    public Node(InetSocketAddress address) {
        this.address = address;
    }

    public Node(InetSocketAddress address, Optional<Connection> connection, Details details, long nsPing) {
        this.address = address;
        this.connection = connection;
        this.details = details;
        this.nsPing = nsPing;
    }

    public InetSocketAddress getAddress() {
        return address;
    }

    public void setAddress(InetSocketAddress address) {
        this.address = address;
    }

    public Optional<Connection> getConnection() {
        return connection;
    }

    public void setConnection(Optional<Connection> connection) {
        this.connection = connection;
    }

    public Details getDetails() {
        return details;
    }

    public void setDetails(Details details) {
        this.details = details;
    }

    public long getNsPing() {
        return nsPing;
    }

    public void setNsPing(long nsPing) {
        this.nsPing = nsPing;
    }

    public static class Details {

        private String[] protocols;
        private int version;

        public Details() {}

        public Details(String[] protocols, int version) {
            this.protocols = protocols;
            this.version = version;
        }

        public String[] getProtocols() {
            return protocols;
        }

        public void setProtocols(String[] protocols) {
            this.protocols = protocols;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }
    }

    public boolean isMe(int localPort) {

        AtomicBoolean found = new AtomicBoolean(false);

        try {

            NetworkInterface.getNetworkInterfaces()
                    .asIterator()
                    .forEachRemaining(ni ->
                            ni.inetAddresses().forEach(addr -> {

                                InetSocketAddress socket =
                                        new InetSocketAddress(addr.getHostAddress(), localPort);

                                if (this.equals(new Node(socket))) {
                                    found.set(true);
                                }

                            }));

        } catch (Exception ex) {

            log.trace("Failed to detect self node", ex);

        }

        return found.get();
    }

    public static void send(Packet packet, Node node, Optional<BiConsumer<Node, Future<?>>> consumer) {

        node.getConnection().ifPresent(conn -> {

            ChannelFuture future = conn.getChannel().writeAndFlush(packet);

            future.addListener(f ->
                    consumer.ifPresent(c -> c.accept(node, f)));

        });

    }

    public static Node fromURI(URI uri) {

        int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();

        return new Node(new InetSocketAddress(uri.getHost(), port));
    }

    public static Node fromAddress(String address) throws URISyntaxException {

        return fromURI(new URI(null, address, null, null, null)
                .parseServerAuthority());

    }

    public URI getURI() {

        return UriBuilder.fromPath("/{host}:{port}")
                .build(address.getAddress().getHostAddress(), address.getPort());

    }

    @Override
    public boolean equals(Object o) {

        if (this == o) return true;

        if (!(o instanceof Node other)) return false;

        return getURI().equals(other.getURI());
    }

    @Override
    public int hashCode() {
        return getURI().hashCode();
    }

    public static class NodeBuilder {

        private InetSocketAddress address;
        private Optional<Connection> connection = Optional.empty();
        private Details details = new Details();
        private long nsPing;

        public NodeBuilder address(InetSocketAddress address) {
            this.address = address;
            return this;
        }

        public NodeBuilder connection(Optional<Connection> connection) {
            this.connection = connection;
            return this;
        }

        public NodeBuilder details(Details details) {
            this.details = details;
            return this;
        }

        public NodeBuilder nsPing(long nsPing) {
            this.nsPing = nsPing;
            return this;
        }

        public Node build() {
            return new Node(address, connection, details, nsPing);
        }
    }

    public static NodeBuilder builder() {
        return new NodeBuilder();
    }
}