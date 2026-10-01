/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Objects;
import java.util.Optional;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.StorageSpork;

@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StorageSporkResource extends CDIBridgeResource {
	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@CDIBridgeInject
	private Topology topology;

	@Path("/storage") @GET
	public Response get() {
		final StorageSpork spork = sporkDatabase.getStorageSpork();
		return Objects.isNull(spork) ? Response.noContent().build() : Response.ok().entity(spork).build();
	}

	@Path("/storage") @PUT
	public Response set(@NotNull final StorageSpork.SporkData parameters,
		@NotNull @HeaderParam("privateKey") final String privateKey) {

		if (!NetworkKey.isTrusted(privateKey)) {
			return Response.status(Response.Status.UNAUTHORIZED).build();
		}

		try {
			parameters.validate();
		} catch (IllegalArgumentException ex) {
			return Response.status(Response.Status.BAD_REQUEST).entity(ex.getMessage()).build();
		}

		final StorageSpork spork = ResourceHelper.getNewOrClonedSporkSection(sporkDatabase::getStorageSpork,
			StorageSpork::new);

		spork.archive();
		spork.setData(parameters);

		return ResourceHelper.commitAndSign(spork, privateKey, sporkDatabase, false, signed -> {
			sporkDatabase.setStorageSpork(signed);
			Topology.sendAll(PublishSpork.builder().gridSpork(signed).build(), topology, Optional.empty());
		});
	}
}
