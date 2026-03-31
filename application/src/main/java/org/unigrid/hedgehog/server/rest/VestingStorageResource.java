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

import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;
import org.unigrid.hedgehog.model.spork.GridSpork;

@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VestingStorageResource extends CDIBridgeResource {

    @CDIBridgeInject
    private SporkDatabase sporkDatabase;

    @CDIBridgeInject
    private Topology topology;

    @GET
    @Path("/vesting-storage")
    public Response list() {
        VestingStorage vs = sporkDatabase.getVestingStorage();
        if (vs == null) {
            return Response.noContent().build();
        }
        return Response.ok(vs).build();
    }

    @GET
    @Path("/vesting-storage/{address}")
    public Response get(@NotNull @PathParam("address") String addressValue) {
        VestingStorage vs = sporkDatabase.getVestingStorage();
        if (vs == null) {
            return Response.noContent().build();
        }

        Object rawData = vs.getData();
        if (!(rawData instanceof SporkData)) {
            return Response.serverError().build();
        }
        SporkData data = (SporkData) rawData;

        // ✅ Skapa Address med konstruktor som tar addressValue
        Address address = new Address(addressValue);

        Vesting vesting = data.getVestingAddresses().get(address);
        if (vesting == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(vesting).build();
    }

    @PUT
    @Path("/vesting-storage/{address}")
    public Response grow(
            @NotNull Vesting vesting,
            @NotNull @PathParam("address") String addressValue,
            @NotNull @HeaderParam("privateKey") String privateKey) {

        if (privateKey == null || !NetworkKey.isTrusted(privateKey)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        VestingStorage vs = sporkDatabase.getVestingStorage();
        if (vs == null) {
            vs = new VestingStorage();
        }

        Object rawData = vs.getData();
        if (!(rawData instanceof SporkData)) {
            return Response.serverError().build();
        }
        SporkData data = (SporkData) rawData;

        //  Skapa Address med korrekt konstruktor
        Address address = new Address(addressValue);

        boolean isUpdate = data.getVestingAddresses().containsKey(address);
        data.getVestingAddresses().put(address, vesting);

        // Spara till SporkDatabase
        sporkDatabase.set(vs);

        // Broadcast
        PublishSpork publishSpork = new PublishSpork();
        publishSpork.setGridSpork(vs);

        Topology.sendAll(publishSpork, topology, Optional.empty());

        return isUpdate ? Response.noContent().build() : Response.ok().build();
    }
}