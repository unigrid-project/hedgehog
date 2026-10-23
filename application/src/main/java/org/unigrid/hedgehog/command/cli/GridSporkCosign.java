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
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.client.rest.SporkClient;
import org.unigrid.hedgehog.client.rest.UntrustedSporkException;
import org.unigrid.hedgehog.command.cli.spork.SporkProposal;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.crypto.ActiveKeys;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.util.TerminalText;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "gridspork-cosign",
	description = "Co-sign sporks awaiting a second board key, naming each by its digest from gridspork-pending. "
		+ "What each one sets is shown beside what the node holds before it is signed on this machine."
)
public class GridSporkCosign implements Runnable {
	private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{128}");

	@Mixin
	private BoardKeyOptions boardKey;

	@Parameters(arity = "1..*", description = "Digests of the sporks to co-sign.")
	private List<String> digests;

	@Option(names = "--switched-off", paramLabel = "<type>", description = SporkProposal.SWITCHED_OFF)
	private Set<GridSpork.Type> switchedOff = EnumSet.noneOf(GridSpork.Type.class);

	@Spec
	private CommandSpec spec;

	/* The key is judged once the board keys of the node are known, and by those that a change of the board keys which
	   waits on the node makes active: a key that the change adds co-signs it */
	@Override
	public void run() {
		final String given = boardKey.resolve();

		requireDigests();

		try (RestClient rest = RestClientCommand.connect()) {
			final SporkClient sporks = new SporkClient(rest);

			sporks.installBoardKeys();

			final String key = boardKey.requireBoardKey(given, candidate -> NetworkKey.isTrusted(candidate)
				|| NetworkKey.isOneOf(candidate, proposedBoardKeys(sporks)));

			digests.forEach(digest -> cosign(sporks, digest, key));
		} catch (ResponseOddityException | IOException ex) {
			System.err.println(RestClientCommand.describe(ex));
		} catch (UntrustedSporkException ex) {
			System.err.println("Nothing was co-signed: " + TerminalText.printable(ex.getMessage()));
		}
	}

	/* A change that is not what a current board key proposed adds no key; nor does one that the node cannot hand out,
	   which leaves the node to say what is wrong for each digest */
	private static Set<String> proposedBoardKeys(SporkClient sporks) {
		try {
			final Optional<GridSpork> stored = sporks.stored(GridSpork.Type.BOARD_KEYS);

			return sporks.proposalOf(GridSpork.Type.BOARD_KEYS, stored.orElse(null))
				.flatMap(proposal -> ((BoardKeys) proposal).chainKeys()).map(ActiveKeys::active)
				.orElse(Set.of());
		} catch (ResponseOddityException | UntrustedSporkException | IOException | IllegalArgumentException ex) {
			return Set.of();
		}
	}

	/* A digest goes to the node in a request and is printed with what it answers, so anything else, a key typed in the
	   wrong place among them, is refused before the node is asked and is not repeated */
	private void requireDigests() {
		if (!digests.stream().allMatch(digest -> DIGEST.matcher(digest).matches())) {
			throw new ParameterException(spec.commandLine(), "Every digest is the 128 lower-case hex characters "
				+ "that gridspork-pending lists for a proposal; nothing was co-signed");
		}
	}

	/* Each digest is tried on its own, so one that fails leaves the others to be co-signed */
	private void cosign(SporkClient sporks, String digest, String key) {
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
			System.out.println("Co-signing " + digest + " refused: " + TerminalText.printable(ex.getMessage()));
		}
	}

	/* The proposal is co-signed only once it is known to be what a current board key made from the stored spork,
	   which in turn is known to be what the network took. A stored spork that a key signed which the board switched
	   off since is signed again only by name, by the second board member as by the first. */
	private String cosigned(SporkClient sporks, GridSpork proposal, String digest, String key)
		throws ResponseOddityException, UntrustedSporkException, SigningException, IOException {

		final GridSpork.Type type = proposal.getType();
		final Optional<GridSpork> stored = sporks.held(type);
		final boolean isSwitchedOff = stored.isPresent() && !stored.get().isTakenByTheNetwork();

		if (isSwitchedOff && !switchedOff.contains(type)) {
			throw new UntrustedSporkException("the " + type + " spork that the node holds is "
				+ SporkClient.signingOf(stored.get()) + "; give --switched-off " + type
				+ " to co-sign a renewal of it");
		}

		SporkClient.requireProposalOver(proposal, type, stored.orElse(null));

		if (isSwitchedOff) {
			SporkProposal.showSwitchedOff(stored.get());
		}

		show(proposal, digest, stored);
		proposal.cosign(key);

		final Response answer = sporks.submit(proposal);

		return answer.getStatus() == Status.OK.getStatusCode() ? "Co-signed " + digest
			: "Co-signing " + digest + " refused: " + TerminalText.printable(SporkClient.textOf(answer));
	}

	/* A proposal holds all the data that it sets, the changes of the proposals it replaced among it, so the whole of
	   it and the whole of what the node holds are shown: that is every change that the co-signature accepts */
	private static void show(GridSpork proposal, String digest, Optional<GridSpork> stored)
		throws JsonProcessingException {

		final String signer = NetworkKey.signerOf(proposal).orElse("no current board key");

		System.out.println("Proposal " + digest + " of " + proposal.getType() + ", made at "
			+ proposal.getTimeStamp() + " and signed by " + signer);
		SporkProposal.showHead(stored);
		SporkProposal.show("Stored data:", stored);
		SporkProposal.show("Proposed data:", Optional.of(proposal));
	}
}
