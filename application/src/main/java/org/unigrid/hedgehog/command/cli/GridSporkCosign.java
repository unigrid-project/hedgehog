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

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "gridspork-cosign",
	description = "Co-sign sporks awaiting a second network key, naming each by its digest from gridspork-pending."
)
public class GridSporkCosign extends RestClientCommand {
	@Option(names = { "-k", "--key" }, description = "Hex representation of private key co-signing the sporks.",
		required = true
	)
	private String key;

	@Parameters(arity = "1..*", description = "Digests of the sporks to co-sign.")
	private List<String> digests;

	private String digest;

	public GridSporkCosign() {
		super(HttpMethod.PUT, null, Optional.of(() -> "Unauthorized"));
	}

	@Override
	public void run() {
		setHeaders(new MultivaluedHashMap<>(Map.of("privateKey", key)));

		for (String nextDigest : digests) {
			digest = nextDigest;
			super.run();
		}
	}

	@Override
	protected String getLocation() {
		return "/gridspork/pending/" + digest;
	}

	@Override
	protected <T> Entity<T> getEntity() {
		return (Entity<T>) Entity.text("");
	}

	@Override
	protected void execute(Response response) {
		System.out.println(switch (Status.fromStatusCode(response.getStatus())) {
			case OK -> "Co-signed " + digest;
			case NOT_FOUND -> "No spork awaits a co-signature under " + digest;
			default -> "Co-signing " + digest + " refused: " + response.readEntity(String.class);
		});
	}
}
