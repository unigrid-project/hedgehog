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
import jakarta.ws.rs.core.Response;
import org.unigrid.hedgehog.command.util.RestClientCommand;

public abstract class GridnodeStatusCommand extends RestClientCommand {
	protected GridnodeStatusCommand(String location) {
		super(HttpMethod.PUT, location);
	}

	@Override
	@SuppressWarnings("unchecked")
	protected <T> Entity<T> getEntity() {
		return (Entity<T>) Entity.text("");
	}

	@Override
	protected void execute(Response response) {
		if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
			System.err.println("This daemon was not started with -G, so it is not a gridnode");
		} else {
			System.out.println(response.getStatusInfo());
		}
	}
}
