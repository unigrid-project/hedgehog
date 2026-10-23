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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Function;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.MintEntry;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
public class MintStorageResource extends CDIBridgeResource {
	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@Path("/mint-storage") @GET
	public Response list() {
		final MintStorage ms = sporkDatabase.getMintStorage();

		if (Objects.isNull(ms)) {
			return Response.noContent().build();
		}

		return Response.ok().entity(sporkDatabase.getMintStorage()).build();
	}

	/* The number is a height of the old chain or, for a ledger account, a time in seconds that is above the range of an
	   int, so it is read as a long and bounded by the kind of the address */
	private Response located(String address, long number, Function<Location, Response> action) {
		final Location location;

		try {
			location = Location.builder().address(Address.builder().wif(address).build())
				.height(MintEntry.heightOf(address, number)).build();
		} catch (IllegalArgumentException ex) {
			return Response.status(Response.Status.BAD_REQUEST).entity(ex.getMessage()).build();
		}

		return action.apply(location);
	}

	@Path("/mint-storage/{address}/{height}") @GET
	public Response get(@NotNull @PathParam("address") String address, @NotNull @PathParam("height") long number) {
		return located(address, number, location -> {
			if (Objects.isNull(sporkDatabase.getMintStorage())) {
				return Response.noContent().build();
			}

			final MintStorage.SporkData data = sporkDatabase.getMintStorage().getData();
			final BigDecimal mintAmount = data.getMints().get(location);

			if (Objects.isNull(mintAmount)) {
				return Response.status(Response.Status.NOT_FOUND).build();
			}

			return Response.ok().entity(mintAmount).build();
		});
	}
}
