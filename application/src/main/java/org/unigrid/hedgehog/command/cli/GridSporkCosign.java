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

package org.unigrid.hedgehog.command.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.client.rest.SporkClient;
import org.unigrid.hedgehog.client.rest.UntrustedSporkException;
import org.unigrid.hedgehog.command.cli.spork.SporkProposal;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

@Command(name = "gridspork-cosign",
	description = "Co-sign sporks awaiting a second board key, naming each by its digest from gridspork-pending. "
		+ "What each one sets is shown beside what the node holds before it is signed on this machine."
)
public class GridSporkCosign implements Runnable {
	@Mixin
	private BoardKeyOptions boardKey;

	@Parameters(arity = "1..*", description = "Digests of the sporks to co-sign.")
	private List<String> digests;

	@Override
	public void run() {
		final String key = boardKey.resolve(NetworkKey::isTrusted);

		try (RestClient rest = RestClientCommand.connect()) {
			final SporkClient sporks = new SporkClient(rest);

			digests.forEach(digest -> cosign(sporks, digest, key));
		} catch (IOException ex) {
			System.err.println(RestClientCommand.describe(ex));
		}
	}

	/* Each digest is tried on its own, so one that fails leaves the others to be co-signed */
	private static void cosign(SporkClient sporks, String digest, String key) {
		try {
			final Optional<GridSpork> proposal = sporks.proposal(digest);

			if (proposal.isEmpty()) {
				System.out.println("No spork awaits a co-signature under " + digest);
			} else if (!PendingSporks.digestOf(proposal.get()).equals(digest)) {
				System.out.println("The node answered " + digest + " with another spork, which was not "
					+ "co-signed");
			} else {
				System.out.println(cosigned(sporks, proposal.get(), digest, key));
			}
		} catch (ResponseOddityException ex) {
			System.err.println(RestClientCommand.describe(ex));
		} catch (UntrustedSporkException | SigningException | IllegalArgumentException | IOException ex) {
			System.out.println("Co-signing " + digest + " refused: " + ex.getMessage());
		}
	}

	/* The proposal is co-signed only once it is known to be what a current board key made from the stored spork,
	   which in turn is known to be what the network took */
	private static String cosigned(SporkClient sporks, GridSpork proposal, String digest, String key)
		throws ResponseOddityException, UntrustedSporkException, SigningException, IOException {

		final Optional<GridSpork> stored = sporks.stored(proposal.getType());

		SporkClient.requireProposalOver(proposal, proposal.getType(), stored.orElse(null));
		show(proposal, digest, stored);
		proposal.cosign(key);

		final Response answer = sporks.submit(proposal);

		return answer.getStatus() == Status.OK.getStatusCode() ? "Co-signed " + digest
			: "Co-signing " + digest + " refused: " + answer.readEntity(String.class);
	}

	/* A proposal holds all the data that it sets, the changes of the proposals it replaced among it, so the whole of
	   it and the whole of what the node holds are shown: that is every change that the co-signature accepts */
	private static void show(GridSpork proposal, String digest, Optional<GridSpork> stored)
		throws JsonProcessingException {

		final String signer = NetworkKey.signerOf(proposal).orElse("no known board key");

		System.out.println("Proposal " + digest + " of " + proposal.getType() + ", made at "
			+ proposal.getTimeStamp() + " and signed by " + signer);
		SporkProposal.show("Stored data:", stored);
		SporkProposal.show("Proposed data:", Optional.of(proposal));
	}
}
