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

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.Optional;

import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
public class MintSupplyResource extends CDIBridgeResource {

    @CDIBridgeInject
    private SporkDatabase sporkDatabase;

    @CDIBridgeInject
    private Topology topology;

    @GET
    @Path("/mint-supply")
    public Response list() {

        MintSupply ms = sporkDatabase.getMintSupply();

        if (ms == null) {
            return Response.noContent().build();
        }

        return Response.ok(ms).build();
    }

    @PUT
    @Path("/mint-supply")
    public Response set(BigDecimal maxSupply,
                        @HeaderParam("privateKey") String privateKey) {

        if (privateKey == null || !NetworkKey.isTrusted(privateKey)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        MintSupply ms = sporkDatabase.getMintSupply();

        if (ms == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("MintSupply not initialized")
                    .build();
        }

        ms.getData().setMaxSupply(maxSupply);

        PublishSpork publishSpork = new PublishSpork();
        publishSpork.setGridSpork(ms);

        Topology.sendAll(publishSpork, topology, Optional.empty());

        return Response.ok().build();
    }
}