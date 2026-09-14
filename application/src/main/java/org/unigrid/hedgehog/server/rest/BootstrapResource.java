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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.bootstrap.AddressBalance;
import org.unigrid.hedgehog.model.bootstrap.AddressTransaction;
import org.unigrid.hedgehog.model.bootstrap.BootstrapSnapshot;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;

@Slf4j
@Path("/bootstrap")
@Produces(MediaType.APPLICATION_JSON)
public class BootstrapResource extends CDIBridgeResource {
	private static final int MAXIMUM_PAGE_SIZE = 1000;

	@CDIBridgeInject
	private BootstrapSnapshot snapshot;

	@GET
	public Response info() {
		final Optional<SnapshotReader> reader = snapshot.getReader();

		if (reader.isEmpty()) {
			return unavailable();
		}

		return Response.ok().entity(reader.get().getInfo()).build();
	}

	@Path("/address/{address}") @GET
	public Response balance(@NotNull @PathParam("address") String address) {
		final Optional<SnapshotReader> reader = snapshot.getReader();

		if (reader.isEmpty()) {
			return unavailable();
		}

		try {
			final Optional<AddressBalance> balance = reader.get().balanceOf(address);

			return balance.map(found -> Response.ok().entity(found).build())
				.orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());

		} catch (IllegalArgumentException ex) {
			return malformed(address, ex);
		}
	}

	@Path("/address/{address}/transactions") @GET
	public Response transactions(@NotNull @PathParam("address") String address,
		@QueryParam("offset") @DefaultValue("0") int offset,
		@QueryParam("limit") @DefaultValue("100") int limit) {

		final Optional<SnapshotReader> reader = snapshot.getReader();

		if (reader.isEmpty()) {
			return unavailable();
		}

		try {
			final List<AddressTransaction> transactions = reader.get().transactionsOf(address,
				Math.max(0, offset), Math.min(MAXIMUM_PAGE_SIZE, Math.max(0, limit)));

			return Response.ok().entity(transactions).build();

		} catch (IllegalArgumentException ex) {
			return malformed(address, ex);
		}
	}

	private Response malformed(String address, IllegalArgumentException ex) {
		log.atDebug().log("Rejected the address {}: {}", address, ex.getMessage());
		return Response.status(Response.Status.BAD_REQUEST).build();
	}

	private Response unavailable() {
		log.atDebug().log("No legacy chain snapshot at {}", snapshot.getPath());
		return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
	}
}
