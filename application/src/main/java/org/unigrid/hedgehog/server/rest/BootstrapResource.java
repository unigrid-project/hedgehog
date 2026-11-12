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
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.bootstrap.ChainHeight;
import org.unigrid.hedgehog.model.bootstrap.AddressBalance;
import org.unigrid.hedgehog.model.bootstrap.AddressTransaction;
import org.unigrid.hedgehog.model.bootstrap.BootstrapSnapshot;
import org.unigrid.hedgehog.model.bootstrap.Holder;
import org.unigrid.hedgehog.model.bootstrap.PendingMints;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.server.rest.entity.ErrorResponse;

@Slf4j
@Path("/bootstrap")
@Produces(MediaType.APPLICATION_JSON)
public class BootstrapResource extends CDIBridgeResource {
	private static final int MAXIMUM_PAGE_SIZE = 1000;
	private static final String DEFAULT_OFFSET = "0";
	private static final String DEFAULT_PAGE_SIZE = "100";
	private static final String NO_SNAPSHOT = "The legacy chain snapshot is not loaded yet";
	private static final String NEGATIVE_INDEX = "A holder index cannot be negative";
	private static final String NO_HOLDER = "No holder at index %d; the snapshot has %d addresses";

	@CDIBridgeInject
	private BootstrapSnapshot snapshot;

	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@CDIBridgeInject
	private ChainHeight chainHeight;

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
			final BigDecimal pending = PendingMints.amountFor(address,
				(MintStorage) sporkDatabase.counting(GridSpork.Type.MINT_STORAGE),
				chainHeight.current().orElseThrow()
			);

			if (balance.isEmpty() && pending.signum() == 0) {
				return Response.status(Response.Status.NOT_FOUND).build();
			}

			final AddressBalance found = balance.orElseGet(() -> AddressBalance.builder().address(address)
				.balance(BigDecimal.ZERO).build());

			found.setBalance(found.getBalance().add(pending));
			return Response.ok().entity(found).build();

		} catch (IllegalArgumentException ex) {
			return malformed(address, ex);
		}
	}

	@Path("/address/{address}/transactions") @GET
	public Response transactions(@NotNull @PathParam("address") String address,
		@QueryParam("offset") @DefaultValue(DEFAULT_OFFSET) int offset,
		@QueryParam("limit") @DefaultValue(DEFAULT_PAGE_SIZE) int limit) {

		final Optional<SnapshotReader> reader = snapshot.getReader();

		if (reader.isEmpty()) {
			return unavailable();
		}

		try {
			final List<AddressTransaction> transactions = reader.get().transactionsOf(address,
				offset, Math.min(MAXIMUM_PAGE_SIZE, Math.max(0, limit)));

			return Response.ok().entity(transactions).build();

		} catch (IllegalArgumentException ex) {
			return malformed(address, ex);
		}
	}

	/**
	 * The address at a place in the snapshot's address table, so that the snapshot can be walked without knowing
	 * its addresses first. The balance is the plain snapshot balance, without the mints the chain has not reached.
	 *
	 * @param index the place in the address table, from 0
	 * @return the holder; past the last address 404, for a negative index 400 and while there is no snapshot 503,
	 *	each with an {@link ErrorResponse} saying why
	 */
	@Path("/holders/{index}") @GET
	public Response holder(@PathParam("index") int index) {
		final Optional<SnapshotReader> reader = snapshot.getReader();

		if (reader.isEmpty()) {
			return notLoaded();
		}

		if (index < 0) {
			log.atDebug().log("Rejected the holder index {}", index);
			return error(Response.Status.BAD_REQUEST, NEGATIVE_INDEX);
		}

		return reader.get().holderAt(index).map(holder -> Response.ok().entity(holder).build())
			.orElseGet(() -> error(Response.Status.NOT_FOUND,
				NO_HOLDER.formatted(index, reader.get().addressCount())));
	}

	/**
	 * A page of the snapshot's addresses in index order, paged like the history of an address.
	 *
	 * @param offset the index of the first holder; a negative one counts as 0
	 * @param limit the most holders to give, capped at the largest page
	 * @return the holders of the page, none past the end, or 503 with an {@link ErrorResponse} while there is no
	 *	snapshot
	 */
	@Path("/holders") @GET
	public Response holders(@QueryParam("offset") @DefaultValue(DEFAULT_OFFSET) int offset,
		@QueryParam("limit") @DefaultValue(DEFAULT_PAGE_SIZE) int limit) {

		final Optional<SnapshotReader> reader = snapshot.getReader();

		if (reader.isEmpty()) {
			return notLoaded();
		}

		final List<Holder> holders = reader.get().holders(offset, Math.min(MAXIMUM_PAGE_SIZE, Math.max(0, limit)));

		return Response.ok().entity(holders).build();
	}

	/** Like unavailable(), but says why in the body, as the holder endpoints explain every refusal. */
	private Response notLoaded() {
		log.atDebug().log("No legacy chain snapshot at {}", snapshot.getPath());
		return error(Response.Status.SERVICE_UNAVAILABLE, NO_SNAPSHOT);
	}

	/** An answer with the given status and an {@link ErrorResponse} carrying the reason. */
	private static Response error(Response.Status status, String reason) {
		return Response.status(status).entity(new ErrorResponse(reason)).build();
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
