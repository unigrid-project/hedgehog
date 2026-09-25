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
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporkInfo;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SignatureLogInfo;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo;
import org.unigrid.hedgehog.server.p2p.P2PServer;

@Slf4j
@Path("/gridspork")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
public class GridSporkResource extends CDIBridgeResource {
	@CDIBridgeInject
	private P2PServer p2pServer;

	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@CDIBridgeInject
	private Topology topology;

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

	@Path("/pending") @GET
	public Response pending() {
		final List<PendingSporkInfo> proposals = pendingSporks.list().stream().map(PendingSporkInfo::of).toList();

		return proposals.isEmpty() ? Response.noContent().build() : Response.ok().entity(proposals).build();
	}

	@Path("/pending/{digest}") @PUT
	public Response cosign(@NotNull @PathParam("digest") String digest,
		@NotNull @HeaderParam("privateKey") String privateKey) {

		if (Objects.isNull(privateKey) || !NetworkKey.isTrusted(privateKey)) {
			return Response.status(Response.Status.UNAUTHORIZED).build();
		}

		final Optional<GridSpork> proposal = pendingSporks.find(digest);

		if (proposal.isEmpty()) {
			return Response.status(Response.Status.NOT_FOUND).build();
		}

		final GridSpork spork = SerializationUtils.clone(proposal.get());

		try {
			spork.cosign(privateKey);
		} catch (SigningException ex) {
			log.atWarn().log("Co-signing of spork refused: {}", ex.getMessage());
			return Response.status(Response.Status.CONFLICT).entity(ex.getMessage()).build();
		}

		if (!spork.canReplace(sporkDatabase.get(spork.getType()))) {
			return Response.status(Response.Status.CONFLICT).build();
		}

		sporkDatabase.set(spork);
		pendingSporks.remove(spork.getType());
		Topology.sendAll(PublishSpork.builder().gridSpork(spork).build(), topology, Optional.empty());
		return Response.ok().build();
	}

	@Path("/renew") @PUT
	public Response renew(@NotNull @HeaderParam("privateKey") String privateKey) {
		if (Objects.isNull(privateKey) || !NetworkKey.isTrusted(privateKey)) {
			return Response.status(Response.Status.UNAUTHORIZED).build();
		}

		final List<GridSpork> renewed = new ArrayList<>();

		/* Everything is signed before anything is stored, so a signing failure leaves the database as it was */
		try {
			for (GridSpork stored : storedSporks()) {
				final GridSpork spork = SerializationUtils.clone(stored);

				spork.renew();
				spork.sign(privateKey);
				renewed.add(spork);
			}
		} catch (SigningException ex) {
			log.atWarn().log("Renewal of sporks failed: {}", ex.getMessage());
			return Response.status(Response.Status.UNAUTHORIZED).build();
		}

		if (renewed.isEmpty()) {
			return Response.noContent().build();
		}

		final List<PendingSporkInfo> proposals = new ArrayList<>();

		for (GridSpork spork : renewed) {
			if (pendingSporks.offer(spork, sporkDatabase.get(spork.getType()))) {
				Topology.sendAll(PublishSpork.builder().gridSpork(spork).build(), topology, Optional.empty());
				proposals.add(PendingSporkInfo.of(spork));
			}
		}

		return Response.accepted(proposals).build();
	}

	private List<GridSpork> storedSporks() {
		return Arrays.stream(GridSpork.Type.values()).filter(type -> type != GridSpork.Type.UNDEFINED)
			.map(sporkDatabase::get).filter(Objects::nonNull).toList();
	}
}
