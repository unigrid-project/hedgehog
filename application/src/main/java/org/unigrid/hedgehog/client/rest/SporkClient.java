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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.GridSpork;

/* The sporks of a node as a board member's command line reads them and hands them back: in the form peers exchange, so
   that what is signed on this machine covers the bytes every node reads. A spork the node sends that does not decode
   is an IllegalArgumentException, as it would be from a peer. */
@RequiredArgsConstructor
public class SporkClient {
	public static final String WIRE = "/gridspork/wire";
	public static final String PENDING = "/gridspork/pending";

	private static final ObjectMapper JSON = new ObjectMapper();

	private final RestClient rest;

	public Optional<GridSpork> stored(GridSpork.Type type) throws ResponseOddityException {
		return sporkIn(rest.get(WIRE + "/" + type.name()));
	}

	public Optional<GridSpork> proposal(String digest) throws ResponseOddityException {
		return sporkIn(rest.get(PENDING + "/" + digest));
	}

	/* The node lists its proposals with their digests, by which it hands each one out */
	public Optional<GridSpork> proposalOf(GridSpork.Type type) throws ResponseOddityException, IOException {
		final Response listed = rest.get(PENDING);

		if (listed.getStatus() != Status.OK.getStatusCode()) {
			return Optional.empty();
		}

		for (JsonNode proposal : JSON.readTree(listed.readEntity(String.class))) {
			if (type.name().equals(proposal.path("type").asText())) {
				return proposal(proposal.path("digest").asText());
			}
		}

		return Optional.empty();
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
