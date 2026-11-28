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
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.server.rest.entity.LegacyApproval;
import org.unigrid.hedgehog.service.legacywhitelist.LegacyWhiteListService;
import org.unigrid.hedgehog.service.legacywhitelist.WhiteListAnswer;

/** The legacy whitelist over HTTP. The service decides; this only turns its answers into responses. */
@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
public class LegacyWhiteListResource extends CDIBridgeResource {
	private static final String NOT_AN_ADDRESS = "%s is not a legacy address: %s";
	private static final String NO_WHITELIST = "No legacy whitelist that two board keys signed is held yet";
	private static final String NOT_APPROVED = "%s is not on the legacy whitelist";
	private static final String BEHIND = "This node knows of a newer legacy whitelist than the one it holds;"
		+ " ask again once it has it";
	private static final String NOT_TAKEN = "This release does not take the %s spork";

	@CDIBridgeInject
	private LegacyWhiteListService legacyWhiteListService;

	/**
	 * The whole spork, or 204 while none is held. The board's command also asks here whether the node takes the spork
	 * at all, which is why that 503 carries a reason.
	 */
	@Path("/legacy-whitelist") @GET
	public Response whiteList() {
		if (!legacyWhiteListService.isTaken()) {
			return RestError.of(Response.Status.SERVICE_UNAVAILABLE, NOT_TAKEN.formatted(Type.LEGACY_WHITELIST));
		}

		return legacyWhiteListService.held().map(whitelist -> Response.ok().entity(whitelist).build())
			.orElseGet(() -> Response.noContent().build());
	}

	/** One address: 200 with the signers when approved, otherwise 400 or 404 saying why. */
	@Path("/legacy-whitelist/{address}") @GET
	public Response approval(@NotNull @PathParam("address") String address) {
		return switch (legacyWhiteListService.approvalOf(address)) {
			case WhiteListAnswer.Approved approved -> Response.ok().entity(new LegacyApproval(address, true,
				approved.signedBy(), approved.publishTimeStamp())).build();
			case WhiteListAnswer.NotAnAddress notAnAddress -> RestError.of(Response.Status.BAD_REQUEST,
				NOT_AN_ADDRESS.formatted(address, notAnAddress.reason()));
			case WhiteListAnswer.Behind behind -> RestError.of(Response.Status.NOT_FOUND, BEHIND);
			case WhiteListAnswer.NoSignedList none -> RestError.of(Response.Status.NOT_FOUND, NO_WHITELIST);
			case WhiteListAnswer.NotListed notListed -> RestError.of(Response.Status.NOT_FOUND,
				NOT_APPROVED.formatted(address));
		};
	}
}
