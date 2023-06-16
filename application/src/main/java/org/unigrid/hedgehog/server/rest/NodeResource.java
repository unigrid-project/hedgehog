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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;

@Path("/node")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
public class NodeResource extends CDIBridgeResource {
	@CDIBridgeInject
	private Topology topology;

	private URI getURIFromString(String address) throws URISyntaxException {
		return new URI(null, address, null, null, null).parseServerAuthority();
	}

	@GET
	public Response list() {
		final Set<Node> nodes = topology.cloneNodes();

		if (nodes.isEmpty()) {
			return Response.status(Response.Status.NO_CONTENT).build();
		}

		return Response.ok().entity(nodes).build();
	}

	@Path("/{address}") @GET
	public Response get(@NotNull @PathParam("address") String address) {
		try {
			final Node nodeToFind = Node.fromAddress(address);
			final AtomicReference<Response> response = new AtomicReference<>(
				Response.status(Response.Status.NOT_FOUND).build()
			);

			topology.forEach(n -> {
				if (nodeToFind.equals(n)) {
					response.set(Response.ok().entity(n).build());
				}
			});

			return response.get();

		} catch (URISyntaxException ex) {
			return Response.status(Response.Status.BAD_REQUEST).build();
		}
	}

	@POST
	public Response add(@NotNull String address) {
		try {
			final Node node = Node.fromAddress(address);

			if (topology.containsNode(node)) {
				return Response.status(Response.Status.CONFLICT).build();
			}

			if (topology.addNode(node)) {
				return Response.created(node.getURI()).build();
			} else {
				return Response.notModified().build();
			}

		} catch (URISyntaxException ex) {
			return Response.status(Response.Status.BAD_REQUEST).build();
		}
	}

	@Path("/{address}") @DELETE
	public Response remove(@NotNull @PathParam("address") String address) {
		try {
			final Node nodeToFind = Node.fromAddress(address);

			if (!topology.containsNode(nodeToFind)) {
				return Response.status(Response.Status.NOT_FOUND).build();
			}

			topology.removeNode(nodeToFind);
			return Response.ok().build();

		} catch (URISyntaxException ex)  {
			return Response.status(Response.Status.BAD_REQUEST).build();
		}
	}
}
