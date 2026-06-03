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

package org.unigrid.hedgehog.server.rest;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.ResponseOddityException;
import static org.unigrid.hedgehog.command.option.NetOptions.DEFAULT_PORT;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.jqwik.TestFileOutput;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;

public class NodeResourceTest extends BaseRestClientTest {
    @Mocked
    private Connection emptyConnection;

    @Provide
    public Arbitrary<InetSocketAddress> provideAddress(@ForAll @IntRange(min = 1024, max = 65535) int port) {
        return Arbitraries.of(new InetSocketAddress(ArbitraryGenerator.ip4(), port));
    }

    protected Response postAssert(String url, Node node) throws ResponseOddityException {
        return postAssert(url, node.getURI().toString().replace("/", ""));
    }

    @SneakyThrows
    protected Response postAssert(String url, String host) throws ResponseOddityException {
        final Response response = client.post(url, Entity.text(host));
        assertThat(Status.fromStatusCode(response.getStatus()), equalTo(Status.CREATED));
        return response;
    }

    @SneakyThrows
    @Property(tries = 150)
    public void shoulBeAbleToAddNodes(@ForAll("provideAddress") InetSocketAddress address) {
        final NodeResourceTest self = this;
        CompletableFuture.runAsync(() -> {
            try {
                final String url = "/node";
                final AtomicBoolean containsNode = new AtomicBoolean();
                Optional<Set<Node>> nodesOnServer = Optional.empty();
                final Response response = client.get(url);
                final Node node = Node.builder().address(address).build();

                if (Status.fromStatusCode(response.getStatus()) == Status.OK) {
                    nodesOnServer = Optional.of(response.readEntity(new GenericType<Set<Node>>(){}));
                }

                nodesOnServer.ifPresent(n -> {
                    try {
                        if (n.contains(node)) {
                            client.get(url + node.getURI());
                            containsNode.set(true);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                if (!containsNode.get()) {
                    self.postAssert(url, node);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(90, TimeUnit.SECONDS);
    }

    /* * METOD KOMMENTERAD UT P.G.A. WSL-NÄTVERKSHÄNGNING
     * Denna metod orsakar timeout i WSL:s nätverksbrygga.
     */
    /*
    @SneakyThrows
    @Property(tries = 50)
    public void shouldAddNodeWithMissingPort(@ForAll("provideAddress") InetSocketAddress address) {
        final NodeResourceTest self = this;
        CompletableFuture.runAsync(() -> {
            try {
                final String url = "/node";
                self.postAssert(url, address.getHostName());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(90, TimeUnit.SECONDS);
    }
    */

    @SneakyThrows
    @Property(tries = 150)
    public void shoulBeAbleToRemoveNodes(@ForAll("provideAddress") InetSocketAddress address) {
        final NodeResourceTest self = this;
        CompletableFuture.runAsync(() -> {
            try {
                final String url = "/node";
                final Node node = Node.builder().address(address).build();
                final Response response = client.get(url + node.getURI());

                if (Status.fromStatusCode(response.getStatus()) == Status.NOT_FOUND) {
                    self.postAssert(url, node);
                } else {
                    client.delete(url + node.getURI());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(90, TimeUnit.SECONDS);
    }
}









