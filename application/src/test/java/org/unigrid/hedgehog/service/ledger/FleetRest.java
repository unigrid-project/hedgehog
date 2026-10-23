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

package org.unigrid.hedgehog.service.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.client.rest.SporkClient;
import org.unigrid.hedgehog.model.spork.ChunkData;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.content.SporkContent;
import org.unigrid.hedgehog.server.rest.RestServer;

/* A spork as the foundation puts it out: built and signed with the first network key where the command line runs,
   handed to the REST server of one node in its wire form, and co-signed with the second key the same way. The
   server is the real one of the container, so its checks and the spreading to the peers are the daemon's own. */
@RequiredArgsConstructor
final class FleetRest {
	private static final ObjectMapper JSON = new ObjectMapper();

	private final IntFunction<RestServer> serverOf;

	/* The digest by which the node knows the proposal */
	@SneakyThrows
	String propose(final int node, final GridSpork.Type type, final ChunkData data) {
		try (RestClient client = clientOf(node)) {
			final SporkClient sporks = new SporkClient(client);
			final GridSpork stored = sporks.stored(type).orElse(null);
			final GridSpork spork = GridSpork.nextVersion(type, stored, sporks.proposalOf(type, stored));

			spork.setData(data);
			SporkContent.validate(spork, stored);
			spork.sign(FleetSporks.proposerKey());

			final Response response = sporks.submit(spork);

			require(response, Status.ACCEPTED, node);
			return JSON.readTree(response.readEntity(String.class)).get("digest").asText();
		}
	}

	@SneakyThrows
	void cosign(final int node, final String digest) {
		try (RestClient client = clientOf(node)) {
			final SporkClient sporks = new SporkClient(client);
			final GridSpork proposal = sporks.proposal(digest).orElseThrow();

			proposal.cosign(FleetSporks.cosignerKey());
			require(sporks.submit(proposal), Status.OK, node);
		}
	}

	private RestClient clientOf(final int node) {
		final RestServer server = serverOf.apply(node);

		return new RestClient(server.getHostName(), server.getPort(), true, server.getToken());
	}

	private static void require(final Response response, final Status expected, final int node) {
		Validate.isTrue(response.getStatus() == expected.getStatusCode(), "The node %d answered %d, not %d",
			node, response.getStatus(), expected.getStatusCode());
	}
}
