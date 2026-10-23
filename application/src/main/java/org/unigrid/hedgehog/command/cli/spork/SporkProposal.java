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
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.client.rest.SporkClient;
import org.unigrid.hedgehog.client.rest.UntrustedSporkException;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
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

	static final String FIRST_VERSION = "Make the first version of the spork when the node holds none. A first version"
		+ " takes the place of the one the network holds, so give this only for a spork the network has none of.";

	/* What a command judges with the node before anything is signed; an IllegalArgumentException says what is wrong */
	@FunctionalInterface
	public interface Check {
		void check(SporkClient sporks) throws ResponseOddityException, UntrustedSporkException, IOException;
	}

	public static void propose(CommandLine commandLine, GridSpork.Type type, Consumer<GridSpork> change,
		BoardKeyOptions boardKey, boolean firstVersion) {

		propose(commandLine, type, change, boardKey, NetworkKey::isTrusted, sporks -> { }, firstVersion);
	}

	/* A node can answer that it holds no spork of a type when the network holds one, and a first version takes the place
	   of any other first version however old, so one is made only when the operator says so. The key is judged once the
	   board keys of the node are known, as a key that the board added since the release is one of them. */
	public static void propose(CommandLine commandLine, GridSpork.Type type, Consumer<GridSpork> change,
		BoardKeyOptions boardKey, Predicate<String> isSigner, Check check, boolean firstVersion) {

		final String given = boardKey.resolve();

		try (RestClient rest = RestClientCommand.connect()) {
			final SporkClient sporks = new SporkClient(rest);

			sporks.installBoardKeys();

			final String key = boardKey.requireBoardKey(given, isSigner);

			check.check(sporks);

			final Optional<GridSpork> stored = sporks.stored(type);

			if (stored.isEmpty()) {
				requireFirstVersion(commandLine, type, firstVersion);
			}

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

	private static void requireFirstVersion(CommandLine commandLine, GridSpork.Type type, boolean firstVersion) {
		if (!firstVersion) {
			throw new ParameterException(commandLine, "The node holds no " + type + " spork, so nothing was"
				+ " signed; give --first-version to make the first version of it");
		}

		System.out.println("Making the first version of the " + type + " spork, as the node holds none");
	}

	/* The data of a spork as a whole, so that whoever signs sees every change, those carried over among them */
	public static void show(String heading, Optional<GridSpork> spork) throws JsonProcessingException {
		System.out.println(heading);
		System.out.println(spork.isPresent() ? TerminalText.printable(JSON.writeValueAsString(spork.get().getData()))
			: "none");
	}

	/* What is signed over, short enough to hold against what a second node holds: a node can hand out an earlier head
	   that the network took, and only another node tells whether it is the one the network holds */
	public static void showHead(Optional<GridSpork> stored) {
		stored.ifPresent(spork -> System.out.println("Stored " + spork.getType() + " made at " + spork.getTimeStamp()
			+ ", digest " + StringUtils.left(PendingSporks.digestOf(spork), SHOWN_HEX) + ", signed by "
			+ keyOf(spork, spork.getSignature()) + " and " + keyOf(spork, spork.getCosignature())));
	}

	/* A spork that a key signed which the board switched off since counts for nothing, and the node may hand out one
	   that two stolen keys forged, so whoever signs it again sees who signed it and when */
	public static void showSwitchedOff(GridSpork stored) {
		System.out.println("Stored " + stored.getType() + " is " + SporkClient.signingOf(stored) + ", made at "
			+ stored.getTimeStamp() + ", and counts for nothing until it is signed again");
	}

	/* The help of the option that a command names such a spork with */
	public static final String SWITCHED_OFF = "The type of a stored spork that a board key signed which the board"
		+ " switched off since, like MINT_SUPPLY, to sign again: it counts for nothing until then. Give it once for"
		+ " each such type.";

	private static String keyOf(GridSpork spork, byte[] signature) {
		return spork.signerOfHead(signature).map(key -> StringUtils.left(key, SHOWN_HEX)).orElse("no board key");
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

	/* The status, and the proposal as the node holds it, with the digest that gridspork-cosign takes. The status says
	   whether the node took the spork, so an answer too big to read is only said to be so. */
	public static void print(Response response) throws IOException {
		System.out.println(TerminalText.printable(String.valueOf(response.getStatusInfo())));

		if (response.hasEntity()) {
			final boolean held = response.getStatus() == Status.ACCEPTED.getStatusCode();

			try {
				final String answer = SporkClient.textOf(response);

				System.out.println(held ? Json.parse(answer) : TerminalText.printable(answer));
			} catch (IllegalArgumentException ex) {
				System.out.println(ex.getMessage());
			}
		}
	}
}
