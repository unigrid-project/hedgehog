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

package org.unigrid.hedgehog.client.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporks;

/* The sporks of a node as a board member's command line reads them and hands them back: in the form peers exchange, so
   that what is signed on this machine covers the bytes every node reads. The client trusts any certificate, and the
   node may not be what it claims to be either, so nothing it hands out is signed on top of before it is known to be
   what the network took or what a current board key proposed. A spork that does not decode is an
   IllegalArgumentException, as it would be from a peer. */
@RequiredArgsConstructor
public class SporkClient {
	public static final String WIRE = "/gridspork/wire";
	public static final String PENDING = "/gridspork/pending";

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String AHEAD = "stamped ahead of the clock of this machine";

	private final RestClient rest;

	/* The stored spork of a type, once it is known to be one the network took. One stamped further ahead than clocks
	   drift apart would make the next version as far ahead, which no node takes. */
	public Optional<GridSpork> stored(GridSpork.Type type) throws ResponseOddityException, UntrustedSporkException {
		final Optional<GridSpork> stored = sporkIn(rest.get(WIRE + "/" + type.name()));

		if (stored.isPresent() && (stored.get().getType() != type || !stored.get().isTakenByTheNetwork())) {
			throw new UntrustedSporkException("The " + type + " spork that the node holds is not one that board "
				+ "keys signed, so nothing is signed on top of it");
		}

		if (stored.isPresent() && isAheadOfTheClock(stored.get().getTimeStamp())) {
			throw new UntrustedSporkException("The " + type + " spork that the node holds is " + AHEAD
				+ ", made at " + stored.get().getTimeStamp() + ", so nothing is signed on top of it");
		}

		return stored;
	}

	/* A proposal as the node hands it out, which is for the caller to judge with requireProposalOver() */
	public Optional<GridSpork> proposal(String digest) throws ResponseOddityException {
		return sporkIn(rest.get(PENDING + "/" + digest));
	}

	/* The node lists its proposals with their digests, by which it hands each one out. The one of the type is taken
	   only once it is known to be what a current board key made from the stored spork, which is a verified one. */
	public Optional<GridSpork> proposalOf(GridSpork.Type type, GridSpork stored) throws ResponseOddityException,
		IOException, UntrustedSporkException {

		final Response listed = rest.get(PENDING);

		if (listed.getStatus() != Status.OK.getStatusCode()) {
			return Optional.empty();
		}

		for (JsonNode listing : JSON.readTree(listed.readEntity(String.class))) {
			if (type.name().equals(listing.path("type").asText())) {
				final Optional<GridSpork> proposal = proposal(listing.path("digest").asText());

				if (proposal.isPresent()) {
					requireProposalOver(proposal.get(), type, stored);
				}

				return proposal;
			}
		}

		return Optional.empty();
	}

	/* An honest node drops a proposal once it has waited its lifetime and takes none stamped further ahead than clocks
	   drift apart, so one outside that span is an abandoned proposal handed out again, or a forged one */
	public static void requireProposalOver(GridSpork proposal, GridSpork.Type type, GridSpork stored)
		throws UntrustedSporkException {

		if (proposal.getType() != type || !proposal.isProposalOver(stored)) {
			throw new UntrustedSporkException("The " + type + " proposal that the node waits on is not one that "
				+ "a current board key made from the spork it holds, so nothing is signed on top of it");
		}

		final Instant made = proposal.getTimeStamp();
		final boolean isAhead = isAheadOfTheClock(made);

		if (isAhead || made.isBefore(Instant.now().minus(PendingSporks.LIFETIME))) {
			throw new UntrustedSporkException("The " + type + " proposal that the node waits on was made at "
				+ made + ", " + (isAhead ? AHEAD : "longer ago than a proposal waits")
				+ ", so nothing is signed on top of it");
		}
	}

	private static boolean isAheadOfTheClock(Instant stamp) {
		return stamp.isAfter(Instant.now().plus(PendingSporks.LEEWAY));
	}

	public Response submit(GridSpork spork) throws ResponseOddityException {
		return rest.post(WIRE, Entity.entity(SporkWire.encode(spork), MediaType.APPLICATION_OCTET_STREAM));
	}

	private static Optional<GridSpork> sporkIn(Response response) {
		if (response.getStatus() != Status.OK.getStatusCode()) {
			return Optional.empty();
		}

		return Optional.of(SporkWire.decode(response.readEntity(byte[].class)));
	}
}
