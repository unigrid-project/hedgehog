/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Objects;
import java.util.Optional;
import org.unigrid.hedgehog.model.HedgehogConfig;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.cdi.CDIContext;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.MinimumVersionSpork;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.rest.entity.VersionResponse;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
public class UtilResource extends CDIBridgeResource {

	private HedgehogConfig hedgehogConfig = new HedgehogConfig();

	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@CDIBridgeInject
	private Topology topology;

	@Path("/stop/{username}/{password}")
	@POST
	public Response stop(@NotNull @PathParam("username") String username,
		@NotNull @PathParam("password") String password) {
		if (hedgehogConfig.getUsername().equals(username) && hedgehogConfig.getPassword().equals(password)) {
			CDIContext.stop();
			return Response.status(Response.Status.ACCEPTED).build();
		}
		return Response.status(Response.Status.UNAUTHORIZED).build();
	}

	@Path("/version")
	@GET
	public Response version() {
		return Response.status(Response.Status.ACCEPTED).entity(VersionResponse.create()).build();
	}

	@Path("/version/{gridsporkprotocoll}/{hedgehogprotocoll}")
	@PUT
	public Response growVersion(@NotNull String version,
		@NotNull @PathParam("gridsporkprotocoll") String gridsporkProtocoll,
		@NotNull @PathParam("hedgehogprotocoll") String hedgehogProtocoll,
		@NotNull @HeaderParam("privateKey") String privateKey) {

		if (Objects.nonNull(privateKey) && NetworkKey.isTrusted(privateKey)) {
			final MinimumVersionSpork mvs = ResourceHelper.getNewOrClonedSporkSection(
				() -> sporkDatabase.getMinimumVersionSpork(),
				() -> new MinimumVersionSpork()
			);

			final MinimumVersionSpork.SporkData data = mvs.getData();
			final String olVersion = data.getMinimumVersion();
			boolean isUpdate = Objects.nonNull(olVersion);
			final String oldHedgehogVersion = data.getMinimumVersion();
			isUpdate = Objects.nonNull(oldHedgehogVersion);
			final String oldGridsporkVersion = data.getMinimumVersion();
			isUpdate = Objects.nonNull(oldGridsporkVersion);

			mvs.archive();

			data.setMinimumVersion(version);
			data.setMinimumHedgehogProtocoll(hedgehogProtocoll);
			data.setMinimumGridsporkProtocoll(gridsporkProtocoll);

			return ResourceHelper.commitAndSign(mvs, privateKey, sporkDatabase, isUpdate, signable -> {
				sporkDatabase.setMinimumVersionSpork(signable);

				Topology.sendAll(PublishSpork.builder()
					.gridSpork(sporkDatabase.getMinimumVersionSpork()).build(),
					topology, Optional.empty()
				);
			});
		}

		return Response.status(Response.Status.UNAUTHORIZED).build();
	}

	@Path("/minimum/version")
	@GET
	public Response minVersion() throws JsonProcessingException {
		//TODO: get both version and protocoll of this application and the minimum version
		//and protocoll that the network expects!!!!!
		final MinimumVersionSpork mvs = ResourceHelper.getNewOrClonedSporkSection(
			() -> sporkDatabase.getMinimumVersionSpork(),
			() -> new MinimumVersionSpork()
		);
		final MinimumVersionSpork.SporkData data = mvs.getData();
		final String oldVersion = data.getMinimumVersion();
		final String oldHedgehogVersion = data.getMinimumVersion();
		final String oldGridsporkVersion = data.getMinimumVersion();

		ObjectMapper mapper = new ObjectMapper();
		ObjectNode rootNode = mapper.createObjectNode();

		ObjectNode minimumVersionNode = mapper.createObjectNode();
		minimumVersionNode.put("version", oldVersion);
		minimumVersionNode.put("hedgehog_protocoll", oldHedgehogVersion);
		minimumVersionNode.put("gridspork_protocoll", oldGridsporkVersion);

		final String version = VersionResponse.create().getVersion();
		final String hedgehogProtocoll = VersionResponse.create().getProtocols()[0];
		final String gridsporkProtocoll = VersionResponse.create().getProtocols()[1];

		ObjectNode currentVersion = mapper.createObjectNode();
		currentVersion.put("version", version);
		currentVersion.put("hedgehog_protocoll", hedgehogProtocoll);
		currentVersion.put("gridspork_protocoll", gridsporkProtocoll);

		rootNode.set("minimum_version", minimumVersionNode);
		rootNode.set("current_version", currentVersion);

		return Response.status(Response.Status.OK).entity(mapper.writerWithDefaultPrettyPrinter()
			.writeValueAsString(rootNode)).build();
	}
}
