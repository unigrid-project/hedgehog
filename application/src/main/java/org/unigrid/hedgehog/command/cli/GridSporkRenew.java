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
import java.util.Map;
import java.util.Optional;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "gridspork-renew",
	description = "Propose every spork this node holds re-signed with the given key, keeping the spork data "
		+ "unchanged. A second network key accepts the proposals with gridspork-cosign."
)
public class GridSporkRenew extends RestClientCommand {
	@Option(names = { "-k", "--key" }, description = "Hex representation of private key signing the sporks.",
		required = true
	)
	private String key;

	public GridSporkRenew() {
		super(HttpMethod.PUT, "/gridspork/renew", Optional.of(() -> "Unauthorized"));
	}

	@Override
	public void run() {
		setHeaders(new MultivaluedHashMap<>(Map.of("privateKey", key)));
		super.run();
	}

	@Override
	protected <T> Entity<T> getEntity() {
		return (Entity<T>) Entity.text("");
	}

	@Override
	protected void execute(Response response) {
		System.out.println(response.hasEntity() ? Json.parse(response.readEntity(String.class))
			: "No sporks to renew");
	}
}
