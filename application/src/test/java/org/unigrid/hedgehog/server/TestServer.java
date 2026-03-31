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

 package org.unigrid.hedgehog.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.alexpanov.net.FreePortFinder;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.server.rest.RestServer;

@ApplicationScoped
public class TestServer {

    @Inject
    private P2PServer p2p;

    @Inject
    private RestServer rest;

    @Inject
    NetOptions netOptions;

    @Inject
    RestOptions restOptions;

    public P2PServer getP2p() {
        return p2p;
    }

    public RestServer getRest() {
        return rest;
    }

    public static void mockProperties(TestServer server) {

        try {

            int netPort = FreePortFinder.findFreeLocalPort();
            int restPort = FreePortFinder.findFreeLocalPort(netPort + 1);

            server.netOptions.setHost("localhost");
            server.netOptions.setPort(netPort);

            server.restOptions.setHost("localhost");
            server.restOptions.setPort(restPort);

            Network.getSeeds();

        } catch (Exception e) {
            throw new IllegalStateException("Failed to mock properties", e);
        }
    }
}