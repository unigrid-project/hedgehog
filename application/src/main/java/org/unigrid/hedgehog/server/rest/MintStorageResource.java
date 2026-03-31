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
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
public class MintStorageResource extends CDIBridgeResource {

    @CDIBridgeInject
    private SporkDatabase sporkDatabase;

    @CDIBridgeInject
    private Topology topology;

    @GET
    @Path("/mint-storage")
    public Response list() {

        MintStorage ms = sporkDatabase.getMintStorage();

        if (ms == null) {
            return Response.noContent().build();
        }

        return Response.ok(ms).build();
    }

    @GET
    @Path("/mint-storage/{address}/{height}")
    public Response get(@PathParam("address") String addressValue,
                        @PathParam("height") int height) {

        MintStorage ms = sporkDatabase.getMintStorage();

        if (ms == null) {
            return Response.noContent().build();
        }

        // 🔥 ANVÄND RÄTT ADDRESS-KLASS
        MintStorage.Address address = new MintStorage.Address();
        address.setWif(addressValue);

        MintStorage.SporkData.Location location =
                new MintStorage.SporkData.Location();

        location.setAddress(address);
        location.setHeight(height);

        BigDecimal mintAmount =
                ms.getData().getMints().get(location);

        if (mintAmount == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return Response.ok(mintAmount).build();
    }

    @PUT
    @Path("/mint-storage/{address}/{height}")
    public Response grow(BigDecimal mintAmount,
                         @PathParam("address") String addressValue,
                         @PathParam("height") int height,
                         @HeaderParam("privateKey") String privateKey) {

        if (privateKey == null || !NetworkKey.isTrusted(privateKey)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        MintStorage ms = sporkDatabase.getMintStorage();

        if (ms == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("MintStorage not initialized")
                    .build();
        }

        MintStorage.Address address = new MintStorage.Address();
        address.setWif(addressValue);

        MintStorage.SporkData.Location location =
                new MintStorage.SporkData.Location();

        location.setAddress(address);
        location.setHeight(height);

        ms.getData().getMints().put(location, mintAmount);

        PublishSpork publishSpork = new PublishSpork();
        publishSpork.setGridSpork(ms);

        Topology.sendAll(publishSpork, topology, Optional.empty());

        return Response.ok().build();
    }
}