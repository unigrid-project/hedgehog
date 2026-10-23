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

package org.unigrid.hedgehog.command.cli.spork;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.client.rest.SporkClient;
import org.unigrid.hedgehog.client.rest.UntrustedSporkException;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.Json;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.spork.ChunkData;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.content.SporkContent;
import org.unigrid.hedgehog.model.util.TerminalText;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/* A change to a spork as a board member makes it: the next version is built on this machine over what the node holds
   and the proposal it waits on, once both are known to be what the board signed, judged by the rules of its type,
   shown in full, signed with the board key and handed to the node, which holds it until a second board key co-signs
   it. The key never leaves this machine. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkProposal {
	private static final ObjectWriter JSON = new JsonConfiguration().getContext(ChunkData.class)
		.writerWithDefaultPrettyPrinter();

	private static final int SHOWN_HEX = 16;

	public static void propose(CommandLine commandLine, GridSpork.Type type, Consumer<GridSpork> change, String key) {
		try (RestClient rest = RestClientCommand.connect()) {
			final SporkClient sporks = new SporkClient(rest);
			final Optional<GridSpork> stored = sporks.stored(type);
			final GridSpork spork = GridSpork.nextVersion(type, stored.orElse(null),
				sporks.proposalOf(type, stored.orElse(null)));

			change.accept(spork);
			requireValid(commandLine, spork, stored.orElse(null));
			showHead(stored);
			show("Stored " + type + " data:", stored);
			show("Proposed " + type + " data, signed now:", Optional.of(spork));
			spork.sign(key);
			print(sporks.submit(spork));
		} catch (ResponseOddityException | IOException ex) {
			System.err.println(RestClientCommand.describe(ex));
		} catch (UntrustedSporkException ex) {
			System.err.println("Nothing was signed: " + ex.getMessage());
		} catch (SigningException | IllegalArgumentException ex) {
			System.err.println("The spork was not proposed: " + TerminalText.printable(ex.getMessage()));
		}
	}

	/* The data of a spork as a whole, so that whoever signs sees every change, those carried over among them */
	public static void show(String heading, Optional<GridSpork> spork) throws JsonProcessingException {
		System.out.println(heading);
		System.out.println(spork.isPresent() ? TerminalText.printable(JSON.writeValueAsString(spork.get().getData()))
			: "none");
	}

	/* What is signed over, short enough to hold against what a second node holds: a node can hand out a head that
	   passes for one the network took, signed once by a key the board has retired in the form of a build from
	   before co-signatures, and only another node tells whether it is the one the network holds */
	public static void showHead(Optional<GridSpork> stored) {
		stored.ifPresent(spork -> System.out.println("Stored " + spork.getType() + " made at " + spork.getTimeStamp()
			+ ", digest " + StringUtils.left(PendingSporks.digestOf(spork), SHOWN_HEX)
			+ ", " + signersOf(spork)));
	}

	private static String signersOf(GridSpork spork) {
		final String signer = keyOf(spork, spork.getSignature());

		return spork.isOfAnEarlierBuild()
			? "signed once by " + signer + ", in the form of a build from before co-signatures"
			: "signed by " + signer + " and " + keyOf(spork, spork.getCosignature());
	}

	private static String keyOf(GridSpork spork, byte[] signature) {
		return spork.knownSignerOfHead(signature).map(key -> StringUtils.left(key, SHOWN_HEX)
			+ (Arrays.asList(NetworkKey.getPublicKeys()).contains(key) ? "" : " (retired key)")
		).orElse("no known key");
	}

	/* What the data of the command asks for is judged with the stored spork, so it is refused like data that does
	   not read, only once the node has been asked */
	private static void requireValid(CommandLine commandLine, GridSpork spork, GridSpork stored) {
		try {
			SporkContent.validate(spork, stored);
		} catch (IllegalArgumentException ex) {
			throw new ParameterException(commandLine, "The spork was not proposed: "
				+ TerminalText.printable(ex.getMessage()), ex);
		}
	}

	/* The status, and the proposal as the node holds it, with the digest that gridspork-cosign takes */
	public static void print(Response response) {
		System.out.println(TerminalText.printable(String.valueOf(response.getStatusInfo())));

		if (response.hasEntity()) {
			final String answer = response.readEntity(String.class);
			final boolean held = response.getStatus() == Status.ACCEPTED.getStatusCode();

			System.out.println(held ? Json.parse(answer) : TerminalText.printable(answer));
		}
	}
}
