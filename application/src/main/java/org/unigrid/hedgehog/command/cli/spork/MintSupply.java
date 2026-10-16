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

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import org.unigrid.hedgehog.model.Json;
import picocli.CommandLine.Command;

@Command(name = "mint-supply")
public class MintSupply extends SporkCommand<String> {
	@Override
	protected String location() {
		return "/gridspork/mint-supply";
	}

	@Override
	protected String read(final String data) {
		return data;
	}

	@Override
	protected Entity<?> entityOf(final String data) {
		return Entity.text(data);
	}

	/* This daemon answers with the proposal that was made */
	@Override
	protected void printProposalAnswer(final Response response) {
		System.out.println(Json.parse(response.readEntity(String.class)));
	}
}
