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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.rest.entity.LedgerAccount;
import org.unigrid.hedgehog.server.rest.entity.LedgerBlock;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.service.ledger.LedgerService;
import org.unigrid.hedgehog.service.ledger.LedgerService.Report;

/* Every request reads the newest ledger the service has at that moment, so a node that replaces its ledger is read
   whole, from one ledger or the other and never a mix, and while there is none the answer is that it is not running */
@Path("/ledger")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LedgerResource extends CDIBridgeResource {
	private static final String NOT_RUNNING = "The ledger is not running";

	@CDIBridgeInject
	private LedgerService ledger;

	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@GET
	public Response info() {
		final Report report = ledger.status();
		final LedgerInfo info = ledger.read(held -> LedgerInfo.of(report, held, minimumFee()))
			.orElseGet(() -> LedgerInfo.of(report));

		return Response.ok().entity(info).build();
	}

	@Path("/account/{address}") @GET
	public Response account(@PathParam("address") final String text) {
		return LedgerText.address(text).map(address -> answer(held -> ok(LedgerAccount.of(held, address))))
			.orElseGet(() -> error(Status.BAD_REQUEST,
				"An address is 64 lower-case hex characters for an account, or a legacy address"));
	}

	@Path("/block/{height}") @GET
	public Response block(@PathParam("height") final String text) {
		return heightOf(text).map(height -> answer(held -> held.block(height).map(block -> ok(LedgerBlock.of(block)))
			.orElseGet(() -> error(Status.NOT_FOUND, "No block at height " + height))))
			.orElseGet(() -> error(Status.BAD_REQUEST, "A height is a whole number that fits a long"));
	}

	private static Optional<Long> heightOf(final String text) {
		try {
			return Optional.of(Long.parseLong(text));
		} catch (NumberFormatException ex) {
			return Optional.empty();
		}
	}

	private Response answer(final Function<Ledger, Response> reading) {
		return ledger.read(reading).orElseGet(() -> error(Status.SERVICE_UNAVAILABLE, NOT_RUNNING));
	}

	/* Unset is not zero: a node with no fee spork refuses every transfer, whatever the fee */
	private String minimumFee() {
		return Optional.ofNullable(sporkDatabase.getLedgerFee()).map(spork -> spork.<LedgerFee.SporkData>getData())
			.map(LedgerFee.SporkData::units).map(LedgerText::coins).orElse(null);
	}

	private static Response ok(final Object entity) {
		return Response.ok().entity(entity).build();
	}

	private static Response error(final Status status, final String message) {
		return Response.status(status).entity(Map.of("error", message)).build();
	}
}
