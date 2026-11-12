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

import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.net.URI;
import org.unigrid.hedgehog.model.util.TerminalText;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "node-add")
public class NodeAdd extends RestClientCommand {
	@Parameters(index = "0", description = "The ip:port combination of the node to add.")
	private String address;

	public NodeAdd() {
		super(HttpMethod.POST, "/node");
	}

	@Override
	protected <T> Entity<T> getEntity() {
		return (Entity<T>) Entity.text(address);
	}

	/* A node that was added answers with where it went. One that a peer told of first and is now configured says so in
	   a text next to that, which an operator is shown as the node said it. An answer with neither is shown by its
	   status. */
	@Override
	protected void execute(Response response) {
		if (Status.fromStatusCode(response.getStatus()) == Status.CONFLICT) {
			System.err.println(response.getStatusInfo());
			return;
		}

		final URI location = response.getLocation();
		final boolean said = response.hasEntity();

		if (location != null) {
			System.out.println(location);
		}

		if (said) {
			System.out.println(TerminalText.printable(response.readEntity(String.class)));
		}

		if (location == null && !said) {
			System.out.println(response.getStatusInfo());
		}
	}

	/* The node answers 304 to its own address and to one it cannot use, which the client reports as the status alone */
	@Override
	protected String refusal(ResponseOddityException oddity) {
		return oddity.getStatusCode() == Status.NOT_MODIFIED.getStatusCode()
			? "The node did not take %s, which is its own address or one it cannot use".formatted(address)
			: super.refusal(oddity);
	}
}
