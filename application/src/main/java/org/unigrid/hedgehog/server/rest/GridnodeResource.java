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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.Collateral;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.GridnodeAnnouncer;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;

@Slf4j
@Path("/gridnode")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
public class GridnodeResource extends CDIBridgeResource {

	@CDIBridgeInject
	private Topology topology;

	@CDIBridgeInject
	private GridnodeAnnouncer announcer;

	@GET @Path("/collateral")
	public Response get() {
		// get number of gridnodes and calculate colleteral
		Set<Gridnode> gridnodes = topology.cloneGridnode();
		int count = 0;

		for (Gridnode g : gridnodes) {
			if (g.getStatus() == Gridnode.Status.ACTIVE) {
				count++;
			}
		}

		final Collateral collateral = new Collateral();
		return Response.ok(collateral.get(count)).build();
	}

	@GET
	public Response list() {
		return Response.ok(topology.cloneGridnode()).build();
	}

	@PUT @Path("/start")
	public Response start() {
		return announce(Gridnode.Status.ACTIVE);
	}

	@PUT @Path("/stop")
	public Response stop() {
		return announce(Gridnode.Status.INACTIVE);
	}

	private Response announce(Gridnode.Status status) {
		return announcer.announce(status).map(entry -> {
			Topology.sendAll(PublishGridnode.builder().gridnode(entry).build(), topology, Optional.empty());
			return Response.accepted().build();
		}).orElseGet(() -> Response.status(Response.Status.CONFLICT).build());
	}
}
