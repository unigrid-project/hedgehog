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
 package org.unigrid.hedgehog.command;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.weld.environment.se.events.ContainerInitialized;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.server.rest.RestServer;
import org.unigrid.hedgehog.command.option.NetOptions;

import picocli.CommandLine.Command;

@Command(name = "daemon")
@ApplicationScoped
public class Daemon {

    private final P2PServer p2pServer;
    private final RestServer restServer;

    public Daemon() {
        this.p2pServer = new P2PServer();
        this.restServer = new RestServer();
    }

    public void onStart(@Observes ContainerInitialized event) throws Exception {

        System.out.println("Starting P2P server...");

        p2pServer.start(
                NetOptions.getHost(),
                NetOptions.getPort()
        );

        System.out.println("REST server is already started via CDI @PostConstruct");
    }

    public P2PServer getP2pServer() {
        return p2pServer;
    }

    public RestServer getRestServer() {
        return restServer;
    }
}