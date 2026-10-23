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
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.client.rest.SporkClient;
import org.unigrid.hedgehog.client.rest.UntrustedSporkException;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.Json;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.spork.ChunkData;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.SporkContent;
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

	public static void propose(CommandLine commandLine, GridSpork.Type type, Consumer<GridSpork> change, String key) {
		try (RestClient rest = RestClientCommand.connect()) {
			final SporkClient sporks = new SporkClient(rest);
			final Optional<GridSpork> stored = sporks.stored(type);
			final GridSpork spork = GridSpork.nextVersion(type, stored.orElse(null),
				sporks.proposalOf(type, stored.orElse(null)));

			change.accept(spork);
			requireValid(commandLine, spork, stored.orElse(null));
			show("Stored " + type + " data:", stored);
			show("Proposed " + type + " data, signed now:", Optional.of(spork));
			spork.sign(key);
			print(sporks.submit(spork));
		} catch (ResponseOddityException | IOException ex) {
			System.err.println(RestClientCommand.describe(ex));
		} catch (UntrustedSporkException ex) {
			System.err.println("Nothing was signed: " + ex.getMessage());
		} catch (SigningException | IllegalArgumentException ex) {
			System.err.println("The spork was not proposed: " + ex.getMessage());
		}
	}

	/* The data of a spork as a whole, so that whoever signs sees every change, those carried over among them */
	public static void show(String heading, Optional<GridSpork> spork) throws JsonProcessingException {
		System.out.println(heading);
		System.out.println(spork.isPresent() ? JSON.writeValueAsString(spork.get().getData()) : "none");
	}

	/* What the data of the command asks for is judged with the stored spork, so it is refused like data that does
	   not read, only once the node has been asked */
	private static void requireValid(CommandLine commandLine, GridSpork spork, GridSpork stored) {
		try {
			SporkContent.validate(spork, stored);
		} catch (IllegalArgumentException ex) {
			throw new ParameterException(commandLine, "The spork was not proposed: " + ex.getMessage(), ex);
		}
	}

	/* The status, and the proposal as the node holds it, with the digest that gridspork-cosign takes */
	public static void print(Response response) {
		System.out.println(response.getStatusInfo());

		if (response.hasEntity()) {
			final String answer = response.readEntity(String.class);
			final boolean held = response.getStatus() == Status.ACCEPTED.getStatusCode();

			System.out.println(held ? Json.parse(answer) : answer);
		}
	}
}
