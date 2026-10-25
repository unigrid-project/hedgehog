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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.spork.BoardKeysInfo;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporkInfo;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SignatureLogInfo;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo;

/* The sporks as JSON, for people to read. A spork is signed on the machine of a board member and handed to the node
   in its wire form (GridSporkWireResource). */
@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
public class GridSporkResource extends CDIBridgeResource {
	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@CDIBridgeInject
	private PendingSporks pendingSporks;

	@GET
	public Response list() {
		return Response.ok().entity(new SporkDatabaseInfo(sporkDatabase)).build();
	}

	@Path("/log") @GET
	public Response signatureLogs() {
		final Map<GridSpork.Type, SignatureLogInfo> logs = new EnumMap<>(GridSpork.Type.class);

		storedSporks().forEach(spork -> logs.put(spork.getType(), SignatureLogInfo.of(spork)));
		return logs.isEmpty() ? Response.noContent().build() : Response.ok().entity(logs).build();
	}

	@Path("/board-keys") @GET
	public Response boardKeys() {
		return Response.ok().entity(BoardKeysInfo.of(sporkDatabase.getBoardKeys())).build();
	}

	@Path("/pending") @GET
	public Response pending() {
		final List<PendingSporkInfo> proposals = pendingSporks.list().stream().map(PendingSporkInfo::of).toList();

		return proposals.isEmpty() ? Response.noContent().build() : Response.ok().entity(proposals).build();
	}

	private List<GridSpork> storedSporks() {
		return Arrays.stream(GridSpork.Type.values()).filter(type -> type != GridSpork.Type.UNDEFINED)
			.map(sporkDatabase::get).filter(Objects::nonNull).toList();
	}
}
