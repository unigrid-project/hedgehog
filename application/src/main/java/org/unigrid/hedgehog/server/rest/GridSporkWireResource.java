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

import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.Optional;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

/* The sporks as a board member signs them on their own machine: the command line reads the stored spork and the
   proposal it builds on in the form peers exchange, so that what it signs covers the bytes every node reads */
@Path("/gridspork")
public class GridSporkWireResource extends CDIBridgeResource {
	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@CDIBridgeInject
	private PendingSporks pendingSporks;

	@Path("/wire/{type}") @GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response stored(@NotNull @PathParam("type") String type) {
		return typeNamed(type).map(found -> Optional.ofNullable(sporkDatabase.get(found))
			.map(GridSporkWireResource::wire).orElseGet(() -> Response.noContent().build())
		).orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
	}

	@Path("/pending/{digest}") @GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response proposal(@NotNull @PathParam("digest") String digest) {
		return pendingSporks.find(digest).map(GridSporkWireResource::wire)
			.orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
	}

	private static Response wire(GridSpork spork) {
		return Response.ok(SporkWire.encode(spork), MediaType.APPLICATION_OCTET_STREAM_TYPE).build();
	}

	private static Optional<GridSpork.Type> typeNamed(String name) {
		return Arrays.stream(GridSpork.Type.values()).filter(type -> type != GridSpork.Type.UNDEFINED)
			.filter(type -> type.name().equals(name)).findFirst();
	}
}
