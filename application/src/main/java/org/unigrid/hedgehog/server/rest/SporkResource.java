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
import java.util.Objects;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

/* The sporks that are shown whole and nothing else: no entry of them has an endpoint of its own */
@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
public class SporkResource extends CDIBridgeResource {
	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@Path("/mint-supply") @GET
	public Response mintSupply() {
		return show(Type.MINT_SUPPLY);
	}

	@Path("/storage") @GET
	public Response storage() {
		return show(Type.STORAGE);
	}

	@Path("/ledger-blacklist") @GET
	public Response ledgerBlacklist() {
		return show(Type.LEDGER_BLACKLIST);
	}

	@Path("/ledger-fee") @GET
	public Response ledgerFee() {
		return show(Type.LEDGER_FEE);
	}

	@Path("/communication") @GET
	public Response communication() {
		return show(Type.COMMUNICATION);
	}

	private Response show(Type type) {
		final GridSpork spork = sporkDatabase.get(type);
		return Objects.isNull(spork) ? Response.noContent().build() : Response.ok().entity(spork).build();
	}
}
