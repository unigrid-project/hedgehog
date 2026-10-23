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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.Optional;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.network.handler.SporkRecipients;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.BoardKeysAuthority;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporkInfo;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;
import org.unigrid.hedgehog.model.spork.content.SporkContent;

/* The sporks as a board member signs them on their own machine: the command line reads the stored spork and the
   proposal it builds on in the form peers exchange, so that what it signs covers the bytes every node reads, and it
   hands the signed spork back in that form. No private key ever reaches the node. */
@Path("/gridspork")
public class GridSporkWireResource extends CDIBridgeResource {
	private static final String UNSIGNED = "No current board key signed the spork, or no second one co-signed it";
	private static final String NOT_NEWER = "The spork does not follow on the stored spork and the proposal held";

	@CDIBridgeInject
	private SporkDatabase sporkDatabase;

	@CDIBridgeInject
	private PendingSporks pendingSporks;

	@CDIBridgeInject
	private Topology topology;

	@CDIBridgeInject
	private SporkLag lag;

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

	/**
	* Takes a spork that a board member signed as this node takes one from a peer: one co-signed by a second current
	* board key that may replace the stored spork is stored in its place (200), one signed once that may wait for its
	* co-signature is held as a proposal (202), and either is sent on to the peers. The data is judged before any
	* signature is, so content that no node can act on is never held or sent (400). A spork that no current board key
	* signed, or whose co-signature is not of a second one, is refused (401), and so is one that does not follow on
	* the stored spork or the proposal held for its type (409).
	*/
	@Path("/wire") @POST
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	@Produces(MediaType.APPLICATION_JSON)
	public Response submit(@NotNull byte[] wire) {
		final GridSpork spork;

		try {
			spork = SporkWire.decode(wire);
			SporkContent.validate(spork, sporkDatabase.get(spork.getType()));
		} catch (IllegalArgumentException ex) {
			return refused(Response.Status.BAD_REQUEST, ex.getMessage());
		}

		if (!isSignedByTheBoard(spork)) {
			return refused(Response.Status.UNAUTHORIZED, UNSIGNED);
		}

		/* Before any decision, as a spork from a peer is, so that a spork this node turns down still tells it that
		   the network has moved on */
		lag.record(spork, sporkDatabase, SporkLag.LOCAL);
		return spork.isPending() ? hold(spork) : store(spork);
	}

	private static boolean isSignedByTheBoard(GridSpork spork) {
		return spork.isValidSignature() && (spork.isPending() || spork.isDoublySigned());
	}

	private Response hold(GridSpork spork) {
		if (!pendingSporks.offer(spork, sporkDatabase.get(spork.getType()))) {
			return refused(Response.Status.CONFLICT, NOT_NEWER);
		}

		SporkRecipients.sendAll(spork, topology);
		return Response.accepted(PendingSporkInfo.of(spork)).build();
	}

	private Response store(GridSpork spork) {
		if (!isStored(spork)) {
			return refused(Response.Status.CONFLICT, NOT_NEWER);
		}

		SporkRecipients.sendAll(spork, topology);
		return Response.ok().build();
	}

	private boolean isStored(GridSpork spork) {
		if (spork instanceof BoardKeys boardKeys) {
			return BoardKeysAuthority.accept(boardKeys, sporkDatabase, pendingSporks, () -> true);
		}

		if (!spork.canReplace(sporkDatabase.get(spork.getType()))) {
			return false;
		}

		sporkDatabase.set(spork);
		pendingSporks.retainProposalsOver(spork);
		return true;
	}

	/* A held proposal is answered with JSON, while a reason is plain text, which the command line shows as it is */
	private static Response refused(Response.Status status, String reason) {
		return Response.status(status).entity(reason).type(MediaType.TEXT_PLAIN_TYPE).build();
	}

	private static Response wire(GridSpork spork) {
		return Response.ok(SporkWire.encode(spork), MediaType.APPLICATION_OCTET_STREAM_TYPE).build();
	}

	private static Optional<GridSpork.Type> typeNamed(String name) {
		return Arrays.stream(GridSpork.Type.values()).filter(type -> type != GridSpork.Type.UNDEFINED)
			.filter(type -> type.name().equals(name)).findFirst();
	}
}
